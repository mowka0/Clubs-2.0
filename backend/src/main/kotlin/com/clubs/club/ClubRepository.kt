package com.clubs.club

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.records.ClubsRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ClubRepository(private val dsl: DSLContext) {

    fun create(request: CreateClubRequest, ownerId: UUID, inviteCode: String? = null): ClubsRecord =
        dsl.insertInto(CLUBS)
            .set(CLUBS.ID, UUID.randomUUID())
            .set(CLUBS.OWNER_ID, ownerId)
            .set(CLUBS.NAME, request.name)
            .set(CLUBS.DESCRIPTION, request.description)
            .set(CLUBS.CATEGORY, ClubCategory.valueOf(request.category))
            .set(CLUBS.ACCESS_TYPE, AccessType.valueOf(request.accessType))
            .set(CLUBS.CITY, request.city)
            .set(CLUBS.DISTRICT, request.district)
            .set(CLUBS.MEMBER_LIMIT, request.memberLimit)
            .set(CLUBS.SUBSCRIPTION_PRICE, request.subscriptionPrice)
            .set(CLUBS.AVATAR_URL, request.avatarUrl)
            .set(CLUBS.RULES, request.rules)
            .set(CLUBS.APPLICATION_QUESTION, request.applicationQuestion)
            .set(CLUBS.INVITE_LINK, inviteCode)
            .set(CLUBS.MEMBER_COUNT, 0)
            .set(CLUBS.ACTIVITY_RATING, 0)
            .set(CLUBS.IS_ACTIVE, true)
            .returning()
            .fetchOne()!!

    fun findByInviteCode(code: String): ClubsRecord? =
        dsl.selectFrom(CLUBS)
            .where(CLUBS.INVITE_LINK.eq(code).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne()

    fun updateInviteCode(id: UUID, code: String): ClubsRecord? {
        dsl.update(CLUBS)
            .set(CLUBS.INVITE_LINK, code)
            .where(CLUBS.ID.eq(id))
            .execute()
        return findById(id)
    }

    fun findById(id: UUID): ClubsRecord? =
        dsl.selectFrom(CLUBS)
            .where(CLUBS.ID.eq(id).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne()

    fun countByOwnerId(ownerId: UUID): Int =
        dsl.selectCount().from(CLUBS)
            .where(CLUBS.OWNER_ID.eq(ownerId).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne(0, Int::class.java) ?: 0

    fun softDelete(id: UUID) {
        dsl.update(CLUBS)
            .set(CLUBS.IS_ACTIVE, false)
            .set(CLUBS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUBS.ID.eq(id))
            .execute()
    }

    fun findAll(filters: ClubFilterParams): PageResponse<ClubListItemDto> {
        var condition = CLUBS.ACCESS_TYPE.ne(AccessType.`private`).and(CLUBS.IS_ACTIVE.eq(true))

        filters.category?.let {
            condition = condition.and(CLUBS.CATEGORY.eq(ClubCategory.valueOf(it)))
        }
        filters.city?.let {
            condition = condition.and(CLUBS.CITY.equalIgnoreCase(it))
        }
        filters.accessType?.let {
            condition = condition.and(CLUBS.ACCESS_TYPE.eq(AccessType.valueOf(it)))
        }
        filters.minPrice?.let {
            condition = condition.and(CLUBS.SUBSCRIPTION_PRICE.ge(it))
        }
        filters.maxPrice?.let {
            condition = condition.and(CLUBS.SUBSCRIPTION_PRICE.le(it))
        }
        filters.search?.let {
            val pattern = "%${it.lowercase()}%"
            condition = condition.and(
                DSL.lower(CLUBS.NAME).like(pattern)
                    .or(DSL.lower(CLUBS.DESCRIPTION).like(pattern))
            )
        }

        val total = dsl.selectCount().from(CLUBS).where(condition)
            .fetchOne(0, Long::class.java) ?: 0L

        val clubs = dsl.selectFrom(CLUBS)
            .where(condition)
            .orderBy(CLUBS.ACTIVITY_RATING.desc())
            .limit(filters.size)
            .offset(filters.page * filters.size)
            .fetch()

        val clubIds = clubs.map { it.id!! }
        val nearestEvents = fetchNearestEvents(clubIds)

        val now = OffsetDateTime.now()
        val newThreshold = now.minusDays(14)

        // Top 10% threshold for "Популярный" tag
        val topRatingThreshold = if (clubs.size >= 10) {
            clubs.sortedByDescending { it.activityRating ?: 0 }
                .take(maxOf(1, clubs.size / 10))
                .last().activityRating ?: 0
        } else null

        val items = clubs.map { club ->
            val tags = mutableListOf<String>()
            if (club.createdAt?.isAfter(newThreshold) == true) tags += "Новый"
            if (topRatingThreshold != null && (club.activityRating ?: 0) >= topRatingThreshold) tags += "Популярный"
            val memberCount = club.memberCount ?: 0
            val memberLimit = club.memberLimit
            if (memberLimit > 0 && memberCount.toDouble() / memberLimit < 0.8) tags += "Свободные места"

            ClubListItemDto(
                id = club.id!!,
                name = club.name,
                category = club.category.literal,
                accessType = club.accessType?.literal ?: "open",
                city = club.city,
                subscriptionPrice = club.subscriptionPrice ?: 0,
                memberCount = memberCount,
                memberLimit = memberLimit,
                avatarUrl = club.avatarUrl,
                nearestEvent = nearestEvents[club.id],
                tags = tags
            )
        }

        val totalPages = if (filters.size == 0) 0 else ((total + filters.size - 1) / filters.size).toInt()
        return PageResponse(
            content = items,
            totalElements = total,
            totalPages = totalPages,
            page = filters.page,
            size = filters.size
        )
    }

    private fun fetchNearestEvents(clubIds: List<UUID>): Map<UUID, NearestEventDto> {
        if (clubIds.isEmpty()) return emptyMap()
        val now = OffsetDateTime.now()

        // For each club get nearest upcoming event
        return clubIds.mapNotNull { clubId ->
            val event = dsl.selectFrom(EVENTS)
                .where(
                    EVENTS.CLUB_ID.eq(clubId)
                        .and(EVENTS.STATUS.eq(EventStatus.upcoming))
                        .and(EVENTS.EVENT_DATETIME.gt(now))
                )
                .orderBy(EVENTS.EVENT_DATETIME.asc())
                .limit(1)
                .fetchOne() ?: return@mapNotNull null

            val goingCount = dsl.selectCount().from(EVENT_RESPONSES)
                .where(
                    EVENT_RESPONSES.EVENT_ID.eq(event.id)
                        .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
                )
                .fetchOne(0, Int::class.java) ?: 0

            clubId to NearestEventDto(
                id = event.id!!,
                title = event.title,
                eventDatetime = event.eventDatetime,
                goingCount = goingCount
            )
        }.toMap()
    }

    fun update(id: UUID, request: UpdateClubRequest): ClubsRecord? {
        val step = dsl.update(CLUBS).set(CLUBS.UPDATED_AT, OffsetDateTime.now())

        // Required-in-DB fields: only touched when non-null (null = "leave as is"),
        // empty string never makes sense for them — validation layer rejects.
        request.name?.let { step.set(CLUBS.NAME, it) }
        request.description?.let { step.set(CLUBS.DESCRIPTION, it) }
        request.city?.let { step.set(CLUBS.CITY, it) }
        request.memberLimit?.let { step.set(CLUBS.MEMBER_LIMIT, it) }
        request.subscriptionPrice?.let { step.set(CLUBS.SUBSCRIPTION_PRICE, it) }

        // Nullable-in-DB fields: null = "leave as is", blank string = "clear to NULL".
        // This lets the frontend explicitly erase a field (delete avatar, clear rules, etc.)
        // while the absent key in the JSON body still means "don't touch".
        request.district?.let { step.set(CLUBS.DISTRICT, it.ifBlank { null }) }
        request.avatarUrl?.let { step.set(CLUBS.AVATAR_URL, it.ifBlank { null }) }
        request.rules?.let { step.set(CLUBS.RULES, it.ifBlank { null }) }
        request.applicationQuestion?.let { step.set(CLUBS.APPLICATION_QUESTION, it.ifBlank { null }) }

        step.where(CLUBS.ID.eq(id)).execute()
        return findById(id)
    }

    fun incrementMemberCount(clubId: UUID) {
        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_COUNT, CLUBS.MEMBER_COUNT.plus(1))
            .where(CLUBS.ID.eq(clubId))
            .execute()
    }

    fun decrementMemberCountSafely(clubId: UUID, delta: Int) {
        if (delta <= 0) return
        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_COUNT, DSL.greatest(CLUBS.MEMBER_COUNT.minus(delta), DSL.`val`(0)))
            .where(CLUBS.ID.eq(clubId))
            .execute()
    }
}
