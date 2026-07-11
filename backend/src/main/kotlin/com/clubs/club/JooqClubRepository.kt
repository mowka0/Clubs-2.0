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
        // Окно (дней) для сигнала «недавняя активность» при сортировке discovery-ленты
        const val ACTIVITY_WINDOW_DAYS = 90L
        // Клуб моложе этого числа дней получает тег «Новый»
        const val NEW_CLUB_DAYS = 14L
        // Минимальный размер выборки, при котором вообще считается порог тега «Популярный»
        const val POPULAR_MIN_CLUBS = 10
    }

    /**
     * Живой счётчик участников для отображения = distinct-строки `memberships`, сейчас принадлежащие
     * клубу — `active`, `frozen` (ждёт первого взноса, слот занят) или `expired` (просрочил продление,
     * слот занят) — ВКЛЮЧАЯ membership организатора. Совпадает с семантикой занятости слотов
     * в countActiveByClubId. Считается напрямую из `memberships`. Старая денормализованная колонка
     * `clubs.member_count` удалена (V33): разбросанный и неполный набор мест инкремента/декремента
     * позволял ей рассинхронизироваться (например, leave→rejoin→leave дважды декрементировал её
     * до 0 у клуба из 2 человек). «Фактическое значение из БД» дрейфовать не может.
     */
    private fun aliveMembers(): org.jooq.Condition =
        MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen, MembershipStatus.expired)

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

    /** Коррелированный подзапрос живого счётчика — для сортировки discovery-ленты по реальному размеру клуба. */
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
            .set(CLUBS.PAYMENT_LINK, request.paymentLink?.ifBlank { null })
            .set(CLUBS.PAYMENT_METHOD_NOTE, request.paymentMethodNote?.ifBlank { null })
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

    override fun updateMemberLimit(id: UUID, memberLimit: Int): Club? {
        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_LIMIT, memberLimit)
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
        // Живые счётчики из `memberships` (active+grace, включая организатора) — не дрейфующая колонка.
        val liveCounts = countLiveMembersByClub(clubIds)

        val newThreshold = now.minusDays(NEW_CLUB_DAYS)

        // «Популярный» = клуб попадает в верхний дециль этой выборки по числу участников.
        // Guard threshold > 0 не даёт странице совсем новых (нулевых) клубов пометить всех подряд —
        // ровно та регрессия, которую вызывал выведенный из строя всегда-нулевой activity_rating.
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
     * Сигнал сортировки по недавней активности: число неотменённых событий клуба с датой в пределах
     * последних [ACTIVITY_WINDOW_DAYS] или запланированных на будущее. Коррелированный подзапрос,
     * чтобы сортировка выполнялась в SQL до пагинации. Производная замена выведенного из строя
     * (вечно нулевого) `activity_rating`.
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

    override fun findNearestEvents(clubIds: List<UUID>): Map<UUID, NearestEventDto> = fetchNearestEvents(clubIds)

    private fun fetchNearestEvents(clubIds: List<UUID>): Map<UUID, NearestEventDto> {
        if (clubIds.isEmpty()) return emptyMap()
        val now = OffsetDateTime.now()

        // Для каждого клуба берём ближайшее предстоящее событие
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

        // Обязательные в БД поля: трогаем только при non-null (null = «оставить как есть»),
        // пустая строка для них не имеет смысла — слой валидации её отклоняет.
        request.name?.let { step.set(CLUBS.NAME, it) }
        request.description?.let { step.set(CLUBS.DESCRIPTION, it) }
        request.city?.let { step.set(CLUBS.CITY, it) }
        request.memberLimit?.let { step.set(CLUBS.MEMBER_LIMIT, it) }
        request.subscriptionPrice?.let { step.set(CLUBS.SUBSCRIPTION_PRICE, it) }

        // Nullable в БД поля: null = «оставить как есть», пустая строка = «очистить в NULL».
        // Так фронтенд может явно стереть поле (удалить аватар, очистить правила и т.д.),
        // а отсутствующий ключ в JSON-теле по-прежнему означает «не трогать».
        request.district?.let { step.set(CLUBS.DISTRICT, it.ifBlank { null }) }
        request.avatarUrl?.let { step.set(CLUBS.AVATAR_URL, it.ifBlank { null }) }
        request.rules?.let { step.set(CLUBS.RULES, it.ifBlank { null }) }
        request.applicationQuestion?.let { step.set(CLUBS.APPLICATION_QUESTION, it.ifBlank { null }) }
        request.paymentLink?.let { step.set(CLUBS.PAYMENT_LINK, it.ifBlank { null }) }
        request.paymentMethodNote?.let { step.set(CLUBS.PAYMENT_METHOD_NOTE, it.ifBlank { null }) }

        step.where(CLUBS.ID.eq(id)).execute()
        return findById(id)
    }
}
