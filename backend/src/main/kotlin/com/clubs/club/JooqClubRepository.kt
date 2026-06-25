package com.clubs.club

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqClubRepository(
    private val dsl: DSLContext,
    private val mapper: ClubMapper
) : ClubRepository {

    private companion object {
        const val ACTIVITY_WINDOW_DAYS = 90L
        const val NEW_CLUB_DAYS = 14L
        const val POPULAR_MIN_CLUBS = 10
    }

    /**
     * Live member count for display = distinct `memberships` rows that are `active` or `grace_period`
     * (a grace member still has access, so they occupy a slot), INCLUDING the organizer's membership.
     * This is computed straight from `memberships`. The old denormalized `clubs.member_count`
     * column was dropped (V33) after a scattered, incomplete set of increment/decrement call sites
     * let it drift out of sync (e.g. a leave→rejoin→leave double-decremented it to 0 for a 2-person
     * club). "Actual value from the DB" can never drift.
     */
    private fun aliveMembers(): org.jooq.Condition =
        MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period)

    private fun countLiveMembers(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(aliveMembers()))
            .fetchOne(0, Int::class.java) ?: 0

    private fun countLiveMembersByClub(ids: Collection<UUID>): Map<UUID, Int> {
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(MEMBERSHIPS.CLUB_ID, DSL.count())
            .from(MEMBERSHIPS)
            .where(MEMBERSHIPS.CLUB_ID.`in`(ids).and(aliveMembers()))
            .groupBy(MEMBERSHIPS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    /** Correlated live-count subquery, for ordering the discovery feed by real membership size. */
    private fun liveMemberCountField(): Field<Int> =
        DSL.field(
            DSL.selectCount().from(MEMBERSHIPS)
                .where(MEMBERSHIPS.CLUB_ID.eq(CLUBS.ID).and(aliveMembers())),
        )

    override fun create(request: CreateClubRequest, ownerId: UUID, inviteCode: String?): Club {
        val record = dsl.insertInto(CLUBS)
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
            .set(CLUBS.IS_ACTIVE, true)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findByInviteCode(code: String): Club? =
        dsl.selectFrom(CLUBS)
            .where(CLUBS.INVITE_LINK.eq(code).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne()
            ?.let(mapper::toDomain)
            ?.let { it.copy(memberCount = countLiveMembers(it.id)) }

    override fun updateInviteCode(id: UUID, code: String): Club? {
        dsl.update(CLUBS)
            .set(CLUBS.INVITE_LINK, code)
            .where(CLUBS.ID.eq(id))
            .execute()
        return findById(id)
    }

    override fun findById(id: UUID): Club? =
        dsl.selectFrom(CLUBS)
            .where(CLUBS.ID.eq(id).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne()
            ?.let(mapper::toDomain)
            ?.copy(memberCount = countLiveMembers(id))

    override fun countByOwnerId(ownerId: UUID): Int =
        dsl.selectCount().from(CLUBS)
            .where(CLUBS.OWNER_ID.eq(ownerId).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetchOne(0, Int::class.java) ?: 0

    override fun countPaidByOwnerId(ownerId: UUID): Int =
        dsl.selectCount().from(CLUBS)
            .where(
                CLUBS.OWNER_ID.eq(ownerId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(CLUBS.SUBSCRIPTION_PRICE.gt(0)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun findIdsByOwnerId(ownerId: UUID): List<UUID> =
        dsl.select(CLUBS.ID)
            .from(CLUBS)
            .where(CLUBS.OWNER_ID.eq(ownerId).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetch(CLUBS.ID)
            .filterNotNull()

    override fun findByIds(ids: Collection<UUID>): List<Club> {
        if (ids.isEmpty()) return emptyList()
        val clubs = dsl.selectFrom(CLUBS)
            .where(CLUBS.ID.`in`(ids).and(CLUBS.IS_ACTIVE.eq(true)))
            .fetch()
            .map(mapper::toDomain)
        val counts = countLiveMembersByClub(clubs.map { it.id })
        return clubs.map { it.copy(memberCount = counts[it.id] ?: 0) }
    }

    override fun softDelete(id: UUID) {
        dsl.update(CLUBS)
            .set(CLUBS.IS_ACTIVE, false)
            .set(CLUBS.UPDATED_AT, OffsetDateTime.now())
            .where(CLUBS.ID.eq(id))
            .execute()
    }

    override fun findAll(filters: ClubFilterParams): PageResponse<ClubListItemDto> {
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

        val now = OffsetDateTime.now()
        val activityWindowStart = now.minusDays(ACTIVITY_WINDOW_DAYS)

        val clubs = dsl.selectFrom(CLUBS)
            .where(condition)
            .orderBy(
                recentActivity(activityWindowStart).desc(),
                liveMemberCountField().desc(),
                CLUBS.CREATED_AT.desc()
            )
            .limit(filters.size)
            .offset(filters.page * filters.size)
            .fetch()

        val clubIds = clubs.map { it.id!! }
        val nearestEvents = fetchNearestEvents(clubIds)
        // Live counts from `memberships` (active+grace, incl. organizer) — never the drift-prone column.
        val liveCounts = countLiveMembersByClub(clubIds)

        val newThreshold = now.minusDays(NEW_CLUB_DAYS)

        // "Популярный" = club is in the top member-count decile of this result set. The
        // threshold > 0 guard stops a page of brand-new (zero-member) clubs from tagging
        // everyone — the exact regression the retired all-zero activity_rating caused.
        val topMemberThreshold = if (clubs.size >= POPULAR_MIN_CLUBS) {
            clubs.map { liveCounts[it.id] ?: 0 }.sortedDescending()
                .take(maxOf(1, clubs.size / 10))
                .last()
        } else null

        val items = clubs.map { club ->
            val memberCount = liveCounts[club.id] ?: 0
            val tags = mutableListOf<String>()
            if (club.createdAt?.isAfter(newThreshold) == true) tags += "Новый"
            if (topMemberThreshold != null && topMemberThreshold > 0 &&
                memberCount >= topMemberThreshold
            ) tags += "Популярный"
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

    /**
     * Recent-activity ordering signal: count of the club's non-cancelled events dated within the
     * last [ACTIVITY_WINDOW_DAYS] or scheduled ahead. A correlated subquery so the sort runs in
     * SQL before pagination. Derived replacement for the retired (permanently-0) `activity_rating`.
     */
    private fun recentActivity(windowStart: OffsetDateTime): Field<Int> =
        DSL.field(
            DSL.selectCount()
                .from(EVENTS)
                .where(
                    EVENTS.CLUB_ID.eq(CLUBS.ID)
                        .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                        .and(EVENTS.EVENT_DATETIME.ge(windowStart))
                )
        )

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

    override fun update(id: UUID, request: UpdateClubRequest): Club? {
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

    override fun linkTelegramGroup(clubId: UUID, telegramGroupId: Long) {
        dsl.update(CLUBS)
            .set(CLUBS.TELEGRAM_GROUP_ID, telegramGroupId)
            .where(CLUBS.ID.eq(clubId))
            .execute()
    }
}
