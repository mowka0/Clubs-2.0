package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_PARTICIPANTS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqSkladchinaRepository(
    private val dsl: DSLContext,
    private val mapper: SkladchinaMapper
) : SkladchinaRepository {

    override fun create(
        skladchina: Skladchina,
        participants: List<Pair<UUID, Long?>>
    ): Skladchina {
        val record = dsl.insertInto(SKLADCHINAS)
            .set(SKLADCHINAS.ID, skladchina.id)
            .set(SKLADCHINAS.CLUB_ID, skladchina.clubId)
            .set(SKLADCHINAS.CREATOR_ID, skladchina.creatorId)
            .set(SKLADCHINAS.TITLE, skladchina.title)
            .set(SKLADCHINAS.DESCRIPTION, skladchina.description)
            .set(SKLADCHINAS.RULES, skladchina.rules)
            .set(SKLADCHINAS.PHOTO_URL, skladchina.photoUrl)
            .set(SKLADCHINAS.TEMPLATE, skladchina.template)
            .set(SKLADCHINAS.PAYMENT_MODE, skladchina.paymentMode)
            .set(SKLADCHINAS.TOTAL_GOAL_KOPECKS, skladchina.totalGoalKopecks)
            .set(SKLADCHINAS.PAYMENT_LINK, skladchina.paymentLink)
            .set(SKLADCHINAS.PAYMENT_METHOD_NOTE, skladchina.paymentMethodNote)
            .set(SKLADCHINAS.EVENT_ID, skladchina.eventId)
            .set(SKLADCHINAS.DEADLINE, skladchina.deadline)
            .set(SKLADCHINAS.AFFECTS_REPUTATION, skladchina.affectsReputation)
            .set(SKLADCHINAS.STATUS, skladchina.status)
            .returning()
            .fetchOne()!!

        if (participants.isNotEmpty()) {
            var insertStep = dsl.insertInto(SKLADCHINA_PARTICIPANTS)
                .columns(
                    SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID,
                    SKLADCHINA_PARTICIPANTS.USER_ID,
                    SKLADCHINA_PARTICIPANTS.EXPECTED_AMOUNT_KOPECKS,
                    SKLADCHINA_PARTICIPANTS.STATUS
                )
            participants.forEach { (userId, expected) ->
                insertStep = insertStep.values(skladchina.id, userId, expected, SkladchinaParticipantStatus.pending)
            }
            insertStep.execute()
        }

        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Skladchina? =
        dsl.selectFrom(SKLADCHINAS).where(SKLADCHINAS.ID.eq(id)).fetchOne()?.let(mapper::toDomain)

    override fun findBlockingByEventId(eventId: UUID): Skladchina? =
        dsl.selectFrom(SKLADCHINAS)
            .where(
                SKLADCHINAS.EVENT_ID.eq(eventId)
                    .and(SKLADCHINAS.STATUS.`in`(SkladchinaStatus.active, SkladchinaStatus.closed_success))
            )
            // Сначала active (кнопка ведёт именно на него); затем самый свежий успешный.
            .orderBy(
                DSL.case_().`when`(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active), 0).otherwise(1).asc(),
                SKLADCHINAS.CREATED_AT.desc()
            )
            .limit(1)
            .fetchOne()?.let(mapper::toDomain)

    override fun findActiveByClub(clubId: UUID): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(SKLADCHINAS.CLUB_ID.eq(clubId).and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)))
            .orderBy(SKLADCHINAS.CREATED_AT.desc())
            .fetch()
            .map(mapper::toDomain)

    override fun findAllByClubWithAggregates(
        clubId: UUID,
        includeCompleted: Boolean
    ): List<SkladchinaWithAggregates> {
        var condition = SKLADCHINAS.CLUB_ID.eq(clubId)
        if (!includeCompleted) {
            condition = condition.and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
        }

        val skladchinas = dsl.selectFrom(SKLADCHINAS)
            .where(condition)
            .orderBy(SKLADCHINAS.CREATED_AT.desc(), SKLADCHINAS.ID.asc())
            .fetch()
            .map(mapper::toDomain)

        if (skladchinas.isEmpty()) return emptyList()

        val ids = skladchinas.map { it.id }

        val participantCounts = dsl.select(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID, DSL.count())
            .from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(ids))
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        val paidCounts = dsl.select(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID, DSL.count())
            .from(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(ids)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid))
            )
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        val collectedSums = dsl.select(
            SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID,
            DSL.sum(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS)
        )
            .from(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(ids)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid))
            )
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to (it.value2()?.toLong() ?: 0L) }

        return skladchinas.map { s ->
            SkladchinaWithAggregates(
                skladchina = s,
                collectedKopecks = collectedSums[s.id] ?: 0L,
                participantCount = participantCounts[s.id] ?: 0,
                paidCount = paidCounts[s.id] ?: 0
            )
        }
    }

    override fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaFeedItem> {
        // Скоуп — сборы, где user либо participant, либо creator. Active клубы only.
        // Включаем И активные, И закрытые — закрытые остаются в истории.
        // Сортировка: active первыми (actionRequired → deadline), closed по closed_at DESC.
        val involvementCondition = SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId)
            .or(SKLADCHINAS.CREATOR_ID.eq(userId))
        val baseCondition = CLUBS.IS_ACTIVE.eq(true)
            .and(involvementCondition)

        // Общее число distinct skladchina_id, удовлетворяющих условию вовлечённости
        val total = dsl.select(DSL.countDistinct(SKLADCHINAS.ID))
            .from(SKLADCHINAS)
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .leftJoin(SKLADCHINA_PARTICIPANTS).on(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(SKLADCHINAS.ID))
            .where(baseCondition)
            .fetchOne(0, Long::class.java) ?: 0L

        // Страница skladchinaIds — distinct, сортировка actionRequired DESC, deadline ASC.
        // actionRequired = user является участником И participant.status='pending'.
        val callerStatus = DSL.field(
            "(SELECT status FROM skladchina_participants WHERE skladchina_id = {0} AND user_id = {1})",
            SkladchinaParticipantStatus::class.java,
            SKLADCHINAS.ID, DSL.value(userId)
        )
        val actionRequiredOrder = DSL.case_()
            .`when`(callerStatus.eq(SkladchinaParticipantStatus.pending), 1)
            .otherwise(0)
        // 0 = active (сверху), 1 = closed (снизу — история)
        val statusBucket = DSL.case_()
            .`when`(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active), 0)
            .otherwise(1)
        // Условный ключ сортировки — для active = deadline, для closed = NULL.
        // SELECT DISTINCT требует чтобы все ORDER BY-выражения были в select list,
        // поэтому выносим как alias `active_sort`.
        val activeSort = DSL.field(
            "CASE WHEN {0} = 'active' THEN {1} END",
            java.time.OffsetDateTime::class.java,
            SKLADCHINAS.STATUS, SKLADCHINAS.DEADLINE
        )

        val skladchinaIds = dsl.selectDistinct(
            SKLADCHINAS.ID,
            SKLADCHINAS.DEADLINE,
            SKLADCHINAS.CLOSED_AT,
            statusBucket.`as`("sb"),
            actionRequiredOrder.`as`("ar"),
            activeSort.`as`("active_sort")
        )
            .from(SKLADCHINAS)
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .leftJoin(SKLADCHINA_PARTICIPANTS).on(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(SKLADCHINAS.ID))
            .where(baseCondition)
            .orderBy(
                DSL.field("sb").asc(),                                       // active группа первой
                DSL.field("ar").desc(),                                      // внутри active — actionRequired сверху
                DSL.field("active_sort").asc().nullsLast(),                  // active: ближайший deadline; closed → NULL → в конец, но они уже разделены через sb
                SKLADCHINAS.CLOSED_AT.desc().nullsLast()                     // closed: свежее закрытие сверху
            )
            .limit(size)
            .offset(page * size)
            .fetch()
            .map { it.get(SKLADCHINAS.ID)!! }

        if (skladchinaIds.isEmpty()) {
            return PageResponse(emptyList(), total, computeTotalPages(total, size), page, size)
        }

        // Читаем строки складчин вместе с данными клуба
        val skladchinaRows = dsl.select(
            SKLADCHINAS.asterisk(),
            CLUBS.NAME.`as`("club_name"),
            CLUBS.AVATAR_URL.`as`("club_avatar_url")
        )
            .from(SKLADCHINAS)
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(SKLADCHINAS.ID.`in`(skladchinaIds))
            .fetch()

        // Батч-агрегаты
        val paidCounts = dsl.select(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID, DSL.count())
            .from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(skladchinaIds)
                .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid)))
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        val participantCounts = dsl.select(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID, DSL.count())
            .from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(skladchinaIds))
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        val collectedSums = dsl.select(
            SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID,
            DSL.sum(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS)
        )
            .from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(skladchinaIds)
                .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid)))
            .groupBy(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to (it.value2()?.toLong() ?: 0L) }

        val myStatuses = dsl.select(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID, SKLADCHINA_PARTICIPANTS.STATUS)
            .from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(skladchinaIds)
                .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId)))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

        // Собираем items, сохраняя порядок страницы
        val skladchinaById = skladchinaRows.associateBy { it.get(SKLADCHINAS.ID)!! }
        val items = skladchinaIds.mapNotNull { id ->
            val row = skladchinaById[id] ?: return@mapNotNull null
            val record = row.into(SKLADCHINAS)
            val skladchina = mapper.toDomain(record)
            MySkladchinaFeedItem(
                skladchina = skladchina,
                clubName = row.get("club_name", String::class.java),
                clubAvatarUrl = row.get("club_avatar_url", String::class.java),
                myStatus = myStatuses[id],
                collectedKopecks = collectedSums[id] ?: 0L,
                participantCount = participantCounts[id] ?: 0,
                paidCount = paidCounts[id] ?: 0
            )
        }

        return PageResponse(items, total, computeTotalPages(total, size), page, size)
    }

    override fun countActionRequired(userId: UUID): Int =
        dsl.selectCount()
            .from(SKLADCHINA_PARTICIPANTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID))
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(
                SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    .and(CLUBS.IS_ACTIVE.eq(true))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun findExpiredActive(now: OffsetDateTime): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active).and(SKLADCHINAS.DEADLINE.lessOrEqual(now)))
            .fetch()
            .map(mapper::toDomain)

    override fun claimClose(id: UUID, status: SkladchinaStatus, closedBy: UUID?, closedAt: OffsetDateTime): Boolean =
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.STATUS, status)
            .set(SKLADCHINAS.CLOSED_AT, closedAt)
            .set(SKLADCHINAS.CLOSED_BY, closedBy)
            .set(SKLADCHINAS.UPDATED_AT, OffsetDateTime.now())
            .where(
                SKLADCHINAS.ID.eq(id)
                    // F5-12: только один из конкурентных закрывателей может перевести active → терминальный.
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
            .execute() > 0

    override fun findParticipantsWithInfo(skladchinaId: UUID): List<SkladchinaParticipantInfo> =
        dsl.select(
            SKLADCHINA_PARTICIPANTS.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            SKLADCHINA_PARTICIPANTS.EXPECTED_AMOUNT_KOPECKS,
            SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS,
            SKLADCHINA_PARTICIPANTS.STATUS,
            SKLADCHINA_PARTICIPANTS.PAID_AT,
            SKLADCHINA_PARTICIPANTS.DECLINE_NOTE,
            SKLADCHINA_PARTICIPANTS.DECLINE_REQUESTED_AT,
            SKLADCHINA_PARTICIPANTS.DECLINE_REJECTED,
            SKLADCHINA_PARTICIPANTS.DECLINE_REJECT_NOTE
        )
            .from(SKLADCHINA_PARTICIPANTS)
            .join(USERS).on(USERS.ID.eq(SKLADCHINA_PARTICIPANTS.USER_ID))
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId))
            .orderBy(SKLADCHINA_PARTICIPANTS.CREATED_AT.asc())
            .fetch { r ->
                SkladchinaParticipantInfo(
                    userId = r.get(SKLADCHINA_PARTICIPANTS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME)!!,
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    expectedAmountKopecks = r.get(SKLADCHINA_PARTICIPANTS.EXPECTED_AMOUNT_KOPECKS),
                    declaredAmountKopecks = r.get(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS),
                    status = r.get(SKLADCHINA_PARTICIPANTS.STATUS)!!,
                    paidAt = r.get(SKLADCHINA_PARTICIPANTS.PAID_AT),
                    declineNote = r.get(SKLADCHINA_PARTICIPANTS.DECLINE_NOTE),
                    declineRequestedAt = r.get(SKLADCHINA_PARTICIPANTS.DECLINE_REQUESTED_AT),
                    declineRejected = r.get(SKLADCHINA_PARTICIPANTS.DECLINE_REJECTED) ?: false,
                    declineRejectNote = r.get(SKLADCHINA_PARTICIPANTS.DECLINE_REJECT_NOTE)
                )
            }

    override fun findParticipants(skladchinaId: UUID): List<SkladchinaParticipant> =
        dsl.selectFrom(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId))
            .fetch()
            .map(mapper::toParticipantDomain)

    override fun findParticipant(skladchinaId: UUID, userId: UUID): SkladchinaParticipant? =
        dsl.selectFrom(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
            )
            .fetchOne()
            ?.let(mapper::toParticipantDomain)

    override fun setParticipantPaid(
        skladchinaId: UUID,
        userId: UUID,
        declaredAmountKopecks: Long,
        paidAt: OffsetDateTime
    ): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS, declaredAmountKopecks)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.paid)
            .set(SKLADCHINA_PARTICIPANTS.PAID_AT, paidAt)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
                    // F5-03: конкурентное закрытие могло только что перевести участника в
                    // expired/released — терминальный статус никогда не перезаписываем (строка
                    // paid поверх expired навсегда противоречила бы уже записанной ledger-записи -40).
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

    override fun setParticipantDeclined(
        skladchinaId: UUID,
        userId: UUID,
        declinedAt: OffsetDateTime
    ): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.declined)
            .set(SKLADCHINA_PARTICIPANTS.DECLINED_AT, declinedAt)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
                    // F5-03: тот же pending-only guard, что и в setParticipantPaid.
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

    override fun revertParticipantToPending(skladchinaId: UUID, userId: UUID): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.pending)
            .setNull(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS)
            .setNull(SKLADCHINA_PARTICIPANTS.PAID_AT)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
                    // A-2: откатываем только реальную оплату; участника, которого конкурентное
                    // закрытие уже перевело в терминальный статус, заново не открываем.
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid))
            )
            .execute()

    override fun requestDecline(skladchinaId: UUID, userId: UUID, note: String, requestedAt: OffsetDateTime): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.DECLINE_NOTE, note)
            .set(SKLADCHINA_PARTICIPANTS.DECLINE_REQUESTED_AT, requestedAt)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
                    .and(SKLADCHINA_PARTICIPANTS.DECLINE_REJECTED.isFalse)
            )
            .execute()

    override fun rejectDeclineRequest(skladchinaId: UUID, userId: UUID, note: String): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.DECLINE_REJECTED, true)
            .set(SKLADCHINA_PARTICIPANTS.DECLINE_REJECT_NOTE, note)
            .setNull(SKLADCHINA_PARTICIPANTS.DECLINE_REQUESTED_AT)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
                    .and(SKLADCHINA_PARTICIPANTS.DECLINE_REQUESTED_AT.isNotNull)
            )
            .execute()

    override fun expirePendingParticipants(skladchinaId: UUID): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.expired_no_response)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

    override fun releasePendingParticipants(skladchinaId: UUID): Int =
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.released)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

    override fun markReputationApplied(skladchinaId: UUID, userId: UUID) {
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.REPUTATION_APPLIED, true)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId))
            )
            .execute()
    }

    override fun sumCollectedKopecks(skladchinaId: UUID): Long {
        val sum = dsl.select(DSL.coalesce(DSL.sum(SKLADCHINA_PARTICIPANTS.DECLARED_AMOUNT_KOPECKS), 0L))
            .from(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid))
            )
            .fetchOne(0, java.math.BigDecimal::class.java) ?: java.math.BigDecimal.ZERO
        return sum.toLong()
    }

    override fun countParticipants(skladchinaId: UUID): Int =
        dsl.selectCount().from(SKLADCHINA_PARTICIPANTS)
            .where(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId))
            .fetchOne(0, Int::class.java) ?: 0

    override fun countReputationAffectingCreatedSince(clubId: UUID, since: OffsetDateTime): Int =
        dsl.selectCount().from(SKLADCHINAS)
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.AFFECTS_REPUTATION.isTrue)
                    .and(SKLADCHINAS.CREATED_AT.greaterThan(since))
                    // Rate limit защищает тумблер «важный сбор» на сборах, выбранных организатором.
                    // split_bill верифицируется посещаемостью (не фармится) и всегда влияет на
                    // репутацию — исключаем его, чтобы сплиты не сжигали клубный лимит «важных» сборов.
                    .and(SKLADCHINAS.TEMPLATE.ne(SkladchinaTemplate.split_bill))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun extendDeadline(skladchinaId: UUID, newDeadline: OffsetDateTime): Int =
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.DEADLINE, newDeadline)
            .where(
                SKLADCHINAS.ID.eq(skladchinaId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    // Дедлайн можно только ОТОДВИГАТЬ вперёд, никогда не приближать.
                    .and(SKLADCHINAS.DEADLINE.lessThan(newDeadline))
            )
            .execute()

    override fun findNeedingDeadlineReminder(now: OffsetDateTime, until: OffsetDateTime): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(
                SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)
                    .and(SKLADCHINAS.AFFECTS_REPUTATION.isTrue)
                    .and(SKLADCHINAS.DEADLINE.greaterThan(now))
                    .and(SKLADCHINAS.DEADLINE.lessOrEqual(until))
                    .and(SKLADCHINAS.REMINDER_SENT_AT.isNull)
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markReminderSent(skladchinaId: UUID, at: OffsetDateTime) {
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.REMINDER_SENT_AT, at)
            .where(SKLADCHINAS.ID.eq(skladchinaId))
            .execute()
    }

    override fun countParticipantsByStatus(skladchinaId: UUID, status: SkladchinaParticipantStatus): Int =
        dsl.selectCount().from(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.eq(skladchinaId)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(status))
            )
            .fetchOne(0, Int::class.java) ?: 0

    private fun computeTotalPages(total: Long, size: Int): Int =
        if (size == 0) 0 else ((total + size - 1) / size).toInt()

    /** Проверяет, что все переданные userIds — активные участники клуба. Возвращает отсутствующих. */
    override fun findNonActiveMembers(clubId: UUID, userIds: Collection<UUID>): Set<UUID> {
        if (userIds.isEmpty()) return emptySet()
        val activeMembers = dsl.select(MEMBERSHIPS.USER_ID)
            .from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.USER_ID.`in`(userIds))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetch()
            .mapNotNull { it.value1() }
            .toSet()
        return userIds.toSet() - activeMembers
    }

    override fun findPendingReputationObligations(userId: UUID, clubId: UUID): List<SkladchinaObligation> =
        dsl.select(SKLADCHINAS.ID, SKLADCHINAS.DEADLINE)
            .from(SKLADCHINA_PARTICIPANTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID))
            .where(
                SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId)
                    .and(SKLADCHINAS.CLUB_ID.eq(clubId))
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    .and(SKLADCHINAS.AFFECTS_REPUTATION.isTrue)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .fetch { r -> SkladchinaObligation(r.get(SKLADCHINAS.ID)!!, r.get(SKLADCHINAS.DEADLINE)!!) }

    override fun deleteParticipantFromActiveSkladchinasInClub(userId: UUID, clubId: UUID): List<UUID> {
        val activeSkladchinaIds = dsl.select(SKLADCHINAS.ID)
            .from(SKLADCHINAS)
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
        return dsl.deleteFrom(SKLADCHINA_PARTICIPANTS)
            .where(
                SKLADCHINA_PARTICIPANTS.USER_ID.eq(userId)
                    .and(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(activeSkladchinaIds))
            )
            .returningResult(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID)
            .fetch()
            .mapNotNull { it.value1() }
    }

    override fun cancelActiveByClub(clubId: UUID): Int {
        val activeSkladchinaIds = dsl.select(SKLADCHINAS.ID)
            .from(SKLADCHINAS)
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
        // Сначала освобождаем pending-участников (pending → released, без репутации), пока их
        // складчины ещё `active` и попадают в выборку. released ⇒ ReputationPolicy.financeKind
        // вернёт null ⇒ ledger-строки нет: удаление клуба не должно никого штрафовать за «молчание»
        // (в отличие от expired_no_response, который closeInternal назначил бы после дедлайна).
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.released)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(activeSkladchinaIds)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

        return dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.STATUS, SkladchinaStatus.cancelled)
            .set(SKLADCHINAS.UPDATED_AT, OffsetDateTime.now())
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
            .execute()
    }

    override fun cancelActiveByEventId(eventId: UUID): Int {
        val activeSkladchinaIds = dsl.select(SKLADCHINAS.ID)
            .from(SKLADCHINAS)
            .where(
                SKLADCHINAS.EVENT_ID.eq(eventId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
        // Сначала освобождаем pending-участников (pending → released, без репутации), пока их
        // сплит ещё active и попадает в выборку — зеркально cancelActiveByClub.
        dsl.update(SKLADCHINA_PARTICIPANTS)
            .set(SKLADCHINA_PARTICIPANTS.STATUS, SkladchinaParticipantStatus.released)
            .where(
                SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID.`in`(activeSkladchinaIds)
                    .and(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.pending))
            )
            .execute()

        return dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.STATUS, SkladchinaStatus.cancelled)
            .set(SKLADCHINAS.UPDATED_AT, OffsetDateTime.now())
            .where(
                SKLADCHINAS.EVENT_ID.eq(eventId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
            )
            .execute()
    }
}
