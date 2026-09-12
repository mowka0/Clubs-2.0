package com.clubs.skladchina

import com.clubs.common.dto.PageResponse
import com.clubs.debt.DebtRepository
import com.clubs.debt.DebtTotals
import com.clubs.debt.OPEN_DEBT_STATUSES
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.DEBTS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_ENROLLMENTS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqSkladchinaRepository(
    private val dsl: DSLContext,
    private val mapper: SkladchinaMapper,
    private val debtRepository: DebtRepository
) : SkladchinaRepository {

    override fun create(skladchina: Skladchina): Skladchina {
        val record = dsl.insertInto(SKLADCHINAS)
            .set(SKLADCHINAS.ID, skladchina.id)
            .set(SKLADCHINAS.CLUB_ID, skladchina.clubId)
            .set(SKLADCHINAS.CREATOR_ID, skladchina.creatorId)
            .set(SKLADCHINAS.TITLE, skladchina.title)
            .set(SKLADCHINAS.DESCRIPTION, skladchina.description)
            .set(SKLADCHINAS.RULES, skladchina.rules)
            .set(SKLADCHINAS.PHOTO_URL, skladchina.photoUrl)
            .set(SKLADCHINAS.KIND, skladchina.kind)
            .set(SKLADCHINAS.AMOUNT_KOPECKS, skladchina.amountKopecks)
            .set(SKLADCHINAS.PAYMENT_LINK, skladchina.paymentLink)
            .set(SKLADCHINAS.PAYMENT_METHOD_NOTE, skladchina.paymentMethodNote)
            .set(SKLADCHINAS.EVENT_ID, skladchina.eventId)
            .set(SKLADCHINAS.DEADLINE, skladchina.deadline)
            .set(SKLADCHINAS.ENROLLMENT_UNTIL, skladchina.enrollmentUntil)
            .set(SKLADCHINAS.MIN_PARTICIPANTS, skladchina.minParticipants)
            .set(SKLADCHINAS.HIDDEN_FROM_USER_ID, skladchina.hiddenFromUserId)
            .set(SKLADCHINAS.STATUS, skladchina.status)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Skladchina? =
        dsl.selectFrom(SKLADCHINAS).where(SKLADCHINAS.ID.eq(id)).fetchOne()?.let(mapper::toDomain)

    override fun findBlockingByEventId(eventId: UUID): Skladchina? =
        dsl.selectFrom(SKLADCHINAS)
            .where(SKLADCHINAS.EVENT_ID.eq(eventId).and(SKLADCHINAS.STATUS.`in`(BLOCKING_STATUSES)))
            // Сначала active (кнопка ведёт именно на него); затем самый свежий собранный.
            .orderBy(
                DSL.case_().`when`(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active), 0).otherwise(1).asc(),
                SKLADCHINAS.CREATED_AT.desc()
            )
            .limit(1)
            .fetchOne()?.let(mapper::toDomain)

    override fun findSplittableEvents(clubId: UUID, notOlderThan: OffsetDateTime, minAttended: Int): List<SplittableEvent> {
        val attendedCount = DSL.count()
        return dsl.select(EVENTS.ID, EVENTS.TITLE, EVENTS.EVENT_DATETIME, attendedCount)
            .from(EVENTS)
            .join(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            // Пришедший, успевший покинуть клуб, не считается: его долю в сборе всё равно не собрать.
            .join(MEMBERSHIPS).on(
                MEMBERSHIPS.USER_ID.eq(EVENT_RESPONSES.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.eq(EventStatus.completed))
                    .and(EVENTS.ATTENDANCE_MARKED.isTrue)
                    .and(EVENTS.EVENT_DATETIME.ge(notOlderThan))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
                    .andNotExists(
                        DSL.selectOne().from(SKLADCHINAS).where(
                            SKLADCHINAS.EVENT_ID.eq(EVENTS.ID).and(SKLADCHINAS.STATUS.`in`(BLOCKING_STATUSES))
                        )
                    )
            )
            .groupBy(EVENTS.ID, EVENTS.TITLE, EVENTS.EVENT_DATETIME)
            .having(attendedCount.ge(minAttended))
            .orderBy(EVENTS.EVENT_DATETIME.desc())
            .fetch {
                SplittableEvent(
                    eventId = it.value1()!!,
                    title = it.value2()!!,
                    eventDatetime = it.value3()!!,
                    attendedCount = it.value4()
                )
            }
    }

    override fun findActiveByClub(clubId: UUID): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(SKLADCHINAS.CLUB_ID.eq(clubId).and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)))
            .orderBy(SKLADCHINAS.CREATED_AT.desc())
            .fetch()
            .map(mapper::toDomain)

    override fun findAllByClubWithAggregates(
        clubId: UUID,
        includeCompleted: Boolean,
        viewerId: UUID
    ): List<SkladchinaWithAggregates> {
        var condition = SKLADCHINAS.CLUB_ID.eq(clubId).and(notHiddenFrom(viewerId))
        if (!includeCompleted) condition = condition.and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))

        val skladchinas = dsl.selectFrom(SKLADCHINAS)
            .where(condition)
            .orderBy(SKLADCHINAS.CREATED_AT.desc(), SKLADCHINAS.ID.asc())
            .fetch()
            .map(mapper::toDomain)
        if (skladchinas.isEmpty()) return emptyList()

        val ids = skladchinas.map { it.id }
        val totals = debtRepository.totalsBatch(ids)
        val enrolled = countEnrolledBatch(ids)
        return skladchinas.map {
            SkladchinaWithAggregates(it, totals[it.id] ?: DebtTotals.EMPTY, enrolled[it.id] ?: 0)
        }
    }

    override fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MySkladchinaFeedItem> {
        // Вовлечённость: создатель, должник (в любом состоянии) или отметившийся «В деле».
        val involved = SKLADCHINAS.CREATOR_ID.eq(userId)
            .or(DSL.exists(dsl.selectOne().from(DEBTS)
                .where(DEBTS.SKLADCHINA_ID.eq(SKLADCHINAS.ID).and(DEBTS.DEBTOR_ID.eq(userId)))))
            .or(DSL.exists(dsl.selectOne().from(SKLADCHINA_ENROLLMENTS)
                .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.eq(SKLADCHINAS.ID).and(SKLADCHINA_ENROLLMENTS.USER_ID.eq(userId)))))
        val baseCondition = CLUBS.IS_ACTIVE.eq(true).and(involved).and(notHiddenFrom(userId))

        val total = dsl.selectCount()
            .from(SKLADCHINAS)
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(baseCondition)
            .fetchOne(0, Long::class.java) ?: 0L

        // «Мне нужно действовать»: мой открытый долг как должника или чужой «Отдал», ждущий моего ответа.
        val actionRequired = DSL.exists(dsl.selectOne().from(DEBTS).where(
            DEBTS.SKLADCHINA_ID.eq(SKLADCHINAS.ID).and(
                DEBTS.DEBTOR_ID.eq(userId).and(DEBTS.STATUS.`in`(DebtStatus.waiting, DebtStatus.promised))
                    .or(DEBTS.CREDITOR_ID.eq(userId).and(DEBTS.STATUS.eq(DebtStatus.claimed)))
            )
        ))
        val statusBucket = DSL.case_().`when`(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active), 0).otherwise(1)
        val actionBucket = DSL.case_().`when`(actionRequired, 0).otherwise(1)

        val rows = dsl.select(SKLADCHINAS.asterisk(), CLUBS.NAME, CLUBS.AVATAR_URL)
            .from(SKLADCHINAS)
            .join(CLUBS).on(CLUBS.ID.eq(SKLADCHINAS.CLUB_ID))
            .where(baseCondition)
            .orderBy(
                statusBucket.asc(),                          // активные первыми
                actionBucket.asc(),                          // внутри активных — где ждут меня
                SKLADCHINAS.DEADLINE.asc().nullsLast(),      // ближайший срок
                SKLADCHINAS.CLOSED_AT.desc().nullsLast(),    // закрытые: свежее закрытие сверху
                SKLADCHINAS.ID.asc()
            )
            .limit(size)
            .offset(page * size)
            .fetch()
        if (rows.isEmpty()) return PageResponse(emptyList(), total, computeTotalPages(total, size), page, size)

        val skladchinas = rows.map { mapper.toDomain(it.into(SKLADCHINAS)) }
        val ids = skladchinas.map { it.id }
        val totals = debtRepository.totalsBatch(ids)
        val myStatuses = dsl.select(DEBTS.SKLADCHINA_ID, DEBTS.STATUS)
            .from(DEBTS)
            .where(DEBTS.SKLADCHINA_ID.`in`(ids).and(DEBTS.DEBTOR_ID.eq(userId)).and(DEBTS.CREDITOR_ID.ne(userId)))
            .fetch()
            .associate { it.value1()!! to it.value2()!!.literal }
        val awaitingMe = dsl.selectDistinct(DEBTS.SKLADCHINA_ID)
            .from(DEBTS)
            .where(DEBTS.SKLADCHINA_ID.`in`(ids).and(DEBTS.CREDITOR_ID.eq(userId)).and(DEBTS.STATUS.eq(DebtStatus.claimed)))
            .fetch()
            .mapNotNull { it.value1() }
            .toSet()

        val items = skladchinas.mapIndexed { i, s ->
            MySkladchinaFeedItem(
                skladchina = s,
                clubName = rows[i].get(CLUBS.NAME)!!,
                clubAvatarUrl = rows[i].get(CLUBS.AVATAR_URL),
                totals = totals[s.id] ?: DebtTotals.EMPTY,
                myDebtStatus = myStatuses[s.id],
                awaitingMyConfirmation = s.id in awaitingMe
            )
        }
        return PageResponse(items, total, computeTotalPages(total, size), page, size)
    }

    override fun claimClose(id: UUID, status: SkladchinaStatus, closedAt: OffsetDateTime): Boolean =
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.STATUS, status)
            .set(SKLADCHINAS.CLOSED_AT, closedAt)
            .set(SKLADCHINAS.UPDATED_AT, closedAt)
            .where(SKLADCHINAS.ID.eq(id).and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)))
            .execute() > 0

    override fun claimLock(id: UUID, lockedAt: OffsetDateTime): Boolean =
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.LOCKED_AT, lockedAt)
            .set(SKLADCHINAS.UPDATED_AT, lockedAt)
            .where(
                SKLADCHINAS.ID.eq(id)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    .and(SKLADCHINAS.ENROLLMENT_UNTIL.isNotNull)
                    .and(SKLADCHINAS.LOCKED_AT.isNull)
            )
            .execute() > 0

    override fun claimOrder(id: UUID, orderedAt: OffsetDateTime): Boolean =
        dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.ORDERED_AT, orderedAt)
            .set(SKLADCHINAS.UPDATED_AT, orderedAt)
            .where(
                SKLADCHINAS.ID.eq(id)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    .and(SKLADCHINAS.KIND.eq(SkladchinaKind.per_head))
                    .and(SKLADCHINAS.ORDERED_AT.isNull)
            )
            .execute() > 0

    override fun findEnrollmentDue(now: OffsetDateTime): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(
                SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)
                    .and(SKLADCHINAS.ENROLLMENT_UNTIL.le(now))
                    .and(SKLADCHINAS.LOCKED_AT.isNull)
            )
            .fetch()
            .map(mapper::toDomain)

    override fun findPerHeadNeedingOrderReminder(now: OffsetDateTime, remindedBefore: OffsetDateTime): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(
                SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)
                    .and(SKLADCHINAS.KIND.eq(SkladchinaKind.per_head))
                    .and(SKLADCHINAS.ORDERED_AT.isNull)
                    .and(SKLADCHINAS.DEADLINE.le(now))
                    .and(SKLADCHINAS.ORDER_REMINDED_AT.isNull.or(SKLADCHINAS.ORDER_REMINDED_AT.le(remindedBefore)))
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markOrderReminded(id: UUID, at: OffsetDateTime) {
        dsl.update(SKLADCHINAS).set(SKLADCHINAS.ORDER_REMINDED_AT, at).where(SKLADCHINAS.ID.eq(id)).execute()
    }

    override fun findNeedingDeadlineReminder(now: OffsetDateTime, until: OffsetDateTime): List<Skladchina> =
        dsl.selectFrom(SKLADCHINAS)
            .where(
                SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)
                    .and(SKLADCHINAS.HIDDEN_FROM_USER_ID.isNull)
                    .and(SKLADCHINAS.DEADLINE.greaterThan(now))
                    .and(SKLADCHINAS.DEADLINE.lessOrEqual(until))
                    .and(SKLADCHINAS.REMINDER_SENT_AT.isNull)
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markReminderSent(id: UUID, at: OffsetDateTime) {
        dsl.update(SKLADCHINAS).set(SKLADCHINAS.REMINDER_SENT_AT, at).where(SKLADCHINAS.ID.eq(id)).execute()
    }

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

    override fun findActiveMemberIds(clubId: UUID): List<UUID> =
        dsl.select(MEMBERSHIPS.USER_ID)
            .from(MEMBERSHIPS)
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)))
            .fetch()
            .mapNotNull { it.value1() }

    override fun cancelActiveByClub(clubId: UUID): Int =
        cancelActive(SKLADCHINAS.CLUB_ID.eq(clubId))

    override fun cancelActiveByEventId(eventId: UUID): Int =
        cancelActive(SKLADCHINAS.EVENT_ID.eq(eventId))

    /** Открытые долги прощаются ДО смены статуса, пока сборы ещё active и попадают в подзапрос. */
    private fun cancelActive(scope: Condition): Int {
        val activeIds = dsl.select(SKLADCHINAS.ID).from(SKLADCHINAS)
            .where(scope.and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)))
        val now = OffsetDateTime.now()
        dsl.update(DEBTS)
            .set(DEBTS.STATUS, DebtStatus.forgiven)
            .set(DEBTS.UPDATED_AT, now)
            .where(DEBTS.SKLADCHINA_ID.`in`(activeIds).and(DEBTS.STATUS.`in`(OPEN_DEBT_STATUSES)))
            .execute()
        return dsl.update(SKLADCHINAS)
            .set(SKLADCHINAS.STATUS, SkladchinaStatus.cancelled)
            .set(SKLADCHINAS.CLOSED_AT, now)
            .set(SKLADCHINAS.UPDATED_AT, now)
            .where(scope.and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active)))
            .execute()
    }

    // --- Этап «Кто в деле?» ---

    override fun addEnrollment(skladchinaId: UUID, userId: UUID): Boolean =
        dsl.insertInto(SKLADCHINA_ENROLLMENTS)
            .set(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID, skladchinaId)
            .set(SKLADCHINA_ENROLLMENTS.USER_ID, userId)
            .onConflictDoNothing()
            .execute() > 0

    override fun removeEnrollment(skladchinaId: UUID, userId: UUID): Int =
        dsl.deleteFrom(SKLADCHINA_ENROLLMENTS)
            .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.eq(skladchinaId).and(SKLADCHINA_ENROLLMENTS.USER_ID.eq(userId)))
            .execute()

    override fun findEnrolledUserIds(skladchinaId: UUID): List<UUID> =
        dsl.select(SKLADCHINA_ENROLLMENTS.USER_ID)
            .from(SKLADCHINA_ENROLLMENTS)
            .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.eq(skladchinaId))
            .orderBy(SKLADCHINA_ENROLLMENTS.CREATED_AT.asc())
            .fetch()
            .mapNotNull { it.value1() }

    override fun countEnrolled(skladchinaId: UUID): Int =
        dsl.selectCount().from(SKLADCHINA_ENROLLMENTS)
            .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.eq(skladchinaId))
            .fetchOne(0, Int::class.java) ?: 0

    override fun isEnrolled(skladchinaId: UUID, userId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(SKLADCHINA_ENROLLMENTS)
                .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.eq(skladchinaId).and(SKLADCHINA_ENROLLMENTS.USER_ID.eq(userId)))
        )

    override fun removeEnrollmentsForUserInClub(userId: UUID, clubId: UUID): List<UUID> {
        val enrollingIds = dsl.select(SKLADCHINAS.ID).from(SKLADCHINAS)
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.active))
                    .and(SKLADCHINAS.LOCKED_AT.isNull)
            )
        return dsl.deleteFrom(SKLADCHINA_ENROLLMENTS)
            .where(SKLADCHINA_ENROLLMENTS.USER_ID.eq(userId).and(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.`in`(enrollingIds)))
            .returningResult(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID)
            .fetch()
            .mapNotNull { it.value1() }
    }

    private fun countEnrolledBatch(ids: Collection<UUID>): Map<UUID, Int> =
        dsl.select(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID, DSL.count())
            .from(SKLADCHINA_ENROLLMENTS)
            .where(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID.`in`(ids))
            .groupBy(SKLADCHINA_ENROLLMENTS.SKLADCHINA_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    private fun notHiddenFrom(userId: UUID): Condition =
        SKLADCHINAS.HIDDEN_FROM_USER_ID.isNull.or(SKLADCHINAS.HIDDEN_FROM_USER_ID.ne(userId))

    private fun computeTotalPages(total: Long, size: Int): Int =
        if (size == 0) 0 else ((total + size - 1) / size).toInt()

    companion object {
        // Сбор по встрече, мешающий создать новый: идущий (открыть его) или собранный (уже собрано).
        private val BLOCKING_STATUSES = listOf(SkladchinaStatus.active, SkladchinaStatus.collected)
    }
}
