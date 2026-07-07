package com.clubs.clubquality

import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipEvent
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.TransactionStatus
import com.clubs.generated.jooq.enums.TransactionType
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.MEMBERSHIP_HISTORY
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import com.clubs.generated.jooq.tables.references.SKLADCHINA_PARTICIPANTS
import com.clubs.generated.jooq.tables.references.TRANSACTIONS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Read-only оконные агрегации для owner-панели «Статистика». Временные окна считаются в Kotlin
 * и биндятся параметрами (без SQL `interval`) — детерминированно и тестируемо, консистентно с
 * [JooqClubQualityRepository]. Тренды сравнивают текущее окно с предыдущим окном той же длины
 * и подавляются (null), когда у предыдущего окна нет базы для сравнения (§9.4–§9.5).
 */
@Repository
class JooqClubStatsRepository(private val dsl: DSLContext) : ClubStatsRepository {

    private companion object {
        // Окно удержания/оттока (продления, ушедшие, вернувшиеся), в днях.
        const val RETENTION_WINDOW_DAYS = 30L
        // Окно вовлечённости (доля откликнувшихся на события), в днях.
        const val ENGAGEMENT_WINDOW_DAYS = 90L
        // Окно метрики оплаты складчин, в днях.
        const val SKLADCHINA_WINDOW_DAYS = 90L
        // Окно блока «внимание» (авто-отклонения заявок, отменённые встречи), в днях.
        const val ATTENTION_WINDOW_DAYS = 90L
        // Заявка считается «зависшей», если ждёт ответа дольше этого числа часов.
        const val STALE_APPLICATION_HOURS = 24L
    }

    /** Процентная метрика по окну плюс признак, была ли у окна база для сравнения. */
    private data class WindowValue(val value: Int, val hasBase: Boolean)

    override fun findClubStats(clubId: UUID): ClubStats? {
        // Фильтр IS_ACTIVE зеркалит аспект @RequiresOrganizer (он отбивает неактивный клуб 404 ещё до
        // этого кода) — репозиторий остаётся самосогласованным, если его вызовут в обход того гейта.
        val meta = dsl.select(CLUBS.SUBSCRIPTION_PRICE, CLUBS.ACCESS_TYPE)
            .from(CLUBS).where(CLUBS.ID.eq(clubId).and(CLUBS.IS_ACTIVE.isTrue)).fetchOne()
            ?: return null
        val isPaid = (meta.value1() ?: 0) > 0
        val isClosed = meta.value2() == AccessType.closed

        val now = OffsetDateTime.now()
        val w30 = now.minusDays(RETENTION_WINDOW_DAYS)
        val w60 = now.minusDays(RETENTION_WINDOW_DAYS * 2)
        val w90 = now.minusDays(ENGAGEMENT_WINDOW_DAYS)
        val w180 = now.minusDays(ENGAGEMENT_WINDOW_DAYS * 2)
        val sklStart = now.minusDays(SKLADCHINA_WINDOW_DAYS)
        val sklPrior = now.minusDays(SKLADCHINA_WINDOW_DAYS * 2)
        val attentionStart = now.minusDays(ATTENTION_WINDOW_DAYS)
        val staleBefore = now.minusHours(STALE_APPLICATION_HOURS)

        val retCur = retentionWindow(clubId, w30, now)
        val retPrior = retentionWindow(clubId, w60, w30)

        // Предыдущее окно переиспользует текущее число живых участников как знаменатель — исторического
        // снапшота membership нет (бэкфилл membership_history не делался, §9.4), так что тренд читает
        // движение уникальных откликнувшихся относительно сегодняшнего ростера. Так задумано; не
        // «чинить» на снапшот.
        val alive = aliveMembers(clubId)
        val engCur = engagementWindow(clubId, alive, w90, null)
        val engPrior = engagementWindow(clubId, alive, w180, w90)

        val sklCur = skladchinaWindow(clubId, sklStart, now)
        val sklPriorValue = skladchinaWindow(clubId, sklPrior, sklStart)

        val pending = if (isClosed) pendingApplications(clubId, staleBefore) else null

        return ClubStats(
            clubType = if (isPaid) ClubType.paid else ClubType.free,
            retentionPercent = if (isPaid && retCur.hasBase) retCur.value else null,
            retentionTrend = if (isPaid && retCur.hasBase) trend(retCur, retPrior) else null,
            churnedThisPeriod = churnedMemberCount(clubId, w30),
            rejoinedThisPeriod = rejoinCount(clubId, w30, now),
            engagementPercent = engCur.value,
            engagementTrend = trend(engCur, engPrior),
            skladchinaPaidPercent = if (sklCur.hasBase) sklCur.value else null,
            skladchinaPaidTrend = if (sklCur.hasBase) trend(sklCur, sklPriorValue) else null,
            pendingApplications = pending?.first,
            stalePendingApplications = pending?.second,
            attendanceDisputes = disputeCount(clubId),
            totalMeetings = totalMeetings(clubId, now),
            autoRejectedApplications = if (isClosed) autoRejectedCount(clubId, attentionStart) else null,
            cancelledMeetings = cancelledMeetings(clubId, attentionStart),
        )
    }

    /** Тренд [current] против [prior]; null, если у [prior] нет базы («мало» неотличимо от «нет данных»). */
    private fun trend(current: WindowValue, prior: WindowValue): Trend? {
        if (!prior.hasBase) return null
        val delta = current.value - prior.value
        val direction = when {
            delta > 0 -> TrendDirection.up
            delta < 0 -> TrendDirection.down
            else -> TrendDirection.flat
        }
        return Trend(direction, delta)
    }

    /** Доля продлений в платном клубе: уникальные продлившие ÷ (продлившие + ушедшие) за [start, end). */
    private fun retentionWindow(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): WindowValue {
        val renewers = dsl.select(DSL.countDistinct(TRANSACTIONS.USER_ID))
            .from(TRANSACTIONS)
            .where(
                TRANSACTIONS.CLUB_ID.eq(clubId)
                    .and(TRANSACTIONS.TYPE.eq(TransactionType.renewal))
                    .and(TRANSACTIONS.STATUS.eq(TransactionStatus.completed))
                    .and(TRANSACTIONS.CREATED_AT.ge(start))
                    .and(TRANSACTIONS.CREATED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0
        val churned = churnCount(clubId, start, end)
        val base = renewers + churned
        val value = if (base > 0) (renewers.toDouble() / base * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, base > 0)
    }

    /**
     * *События* оттока (`left` + `expired`) за [start, end) — счётчик потока, питающий долю продлений
     * (знаменатель рядом с продлениями). Не путать с [churnedMemberCount]/[findChurnedMembers],
     * которые считают win-back-*ростер* (уникальных людей, всё ещё отсутствующих сейчас).
     * В бесплатных клубах `expired` не бывает.
     */
    private fun churnCount(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): Int =
        dsl.selectCount().from(MEMBERSHIP_HISTORY)
            .where(
                MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIP_HISTORY.EVENT.`in`(MembershipEvent.left, MembershipEvent.expired))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(start))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Win-back-предикат: участник с событием `left`/`expired` начиная с [since], который сейчас
     * НЕ состоит в клубе (ушедшие-и-вернувшиеся исключаются — они уже здесь). «Состоит» включает
     * `frozen` (доступ закрыт до подтверждения dues, но это всё ещё участник), поэтому frozen НЕ
     * считается ушедшим. Общий для [churnedMemberCount] (рычаг «Ушли/Не продлили за месяц») и
     * [findChurnedMembers] (drill-down-ростер), чтобы значение рычага совпадало с длиной ростера.
     * Оба делают LEFT JOIN `memberships`.
     */
    private fun churnedMemberCondition(clubId: UUID, since: OffsetDateTime): Condition =
        MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
            .and(MEMBERSHIP_HISTORY.EVENT.`in`(MembershipEvent.left, MembershipEvent.expired))
            .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(since))
            .and(
                MEMBERSHIPS.STATUS.isNull
                    .or(
                        // «Состоит» = active/frozen/expired (expired-должник — всё ещё участник,
                        // он не ушёл; grace_period мёртв и вычищен V46).
                        MEMBERSHIPS.STATUS.notIn(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.expired,
                        ),
                    ),
            )

    /** Уникальные участники, ушедшие (left/expired) с [since] и не вернувшиеся — рычаг «Ушли за месяц». */
    private fun churnedMemberCount(clubId: UUID, since: OffsetDateTime): Int =
        dsl.select(DSL.countDistinct(MEMBERSHIP_HISTORY.USER_ID))
            .from(MEMBERSHIP_HISTORY)
            .leftJoin(MEMBERSHIPS)
            .on(
                MEMBERSHIPS.USER_ID.eq(MEMBERSHIP_HISTORY.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(MEMBERSHIP_HISTORY.CLUB_ID)),
            )
            .where(churnedMemberCondition(clubId, since))
            .fetchOne(0, Int::class.java) ?: 0

    override fun findChurnedMembers(clubId: UUID): List<ChurnedMember> {
        val since = OffsetDateTime.now().minusDays(RETENTION_WINDOW_DAYS)
        val lastLeft = DSL.max(MEMBERSHIP_HISTORY.OCCURRED_AT)
        return dsl.select(MEMBERSHIP_HISTORY.USER_ID, USERS.FIRST_NAME, USERS.LAST_NAME, USERS.AVATAR_URL, lastLeft)
            .from(MEMBERSHIP_HISTORY)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIP_HISTORY.USER_ID))
            .leftJoin(MEMBERSHIPS)
            .on(
                MEMBERSHIPS.USER_ID.eq(MEMBERSHIP_HISTORY.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(MEMBERSHIP_HISTORY.CLUB_ID)),
            )
            .where(churnedMemberCondition(clubId, since))
            .groupBy(MEMBERSHIP_HISTORY.USER_ID, USERS.FIRST_NAME, USERS.LAST_NAME, USERS.AVATAR_URL)
            .orderBy(lastLeft.desc())
            .fetch()
            .map {
                ChurnedMember(
                    userId = it.value1()!!,
                    firstName = it.value2()!!,
                    lastName = it.value3(),
                    avatarUrl = it.value4(),
                    leftAt = it.value5()!!,
                )
            }
    }

    private fun rejoinCount(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): Int =
        dsl.selectCount().from(MEMBERSHIP_HISTORY)
            .where(
                MEMBERSHIP_HISTORY.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIP_HISTORY.EVENT.eq(MembershipEvent.rejoined))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.ge(start))
                    .and(MEMBERSHIP_HISTORY.OCCURRED_AT.lt(end)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** «Живые» membership'ы с доступом (active) — знаменатель вовлечённости. frozen/expired исключены:
     *  без доступа участвовать в событиях нельзя, и их учёт разбавлял бы вовлечённость. */
    private fun aliveMembers(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Уникальные откликнувшиеся ÷ [alive] по неотменённым событиям с `event_datetime >= start`
     * (и `< end`, если [end] задан). Текущее окно передаёт `end = null`, чтобы включить будущие
     * события — так же считается вовлечённость на карточке Discovery; предыдущее окно ограничено
     * с обеих сторон. hasBase = в окне были события.
     */
    private fun engagementWindow(
        clubId: UUID,
        alive: Int,
        start: OffsetDateTime,
        end: OffsetDateTime?,
    ): WindowValue {
        var condition = EVENTS.CLUB_ID.eq(clubId)
            .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            .and(EVENTS.EVENT_DATETIME.ge(start))
        if (end != null) condition = condition.and(EVENTS.EVENT_DATETIME.lt(end))

        val record = dsl.select(DSL.countDistinct(EVENT_RESPONSES.USER_ID), DSL.countDistinct(EVENTS.ID))
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(condition)
            .fetchOne()
        val responders = record?.value1() ?: 0
        val events = record?.value2() ?: 0
        val value = if (alive > 0) (responders.toDouble() / alive * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, events > 0)
    }

    /**
     * Доля оплативших среди «решённых» участников складчин, закрытых в [start, end). Решённые =
     * {paid, declined, expired_no_response}; `pending` (не определился) и `released` (отпущен
     * организатором) исключаются. hasBase = есть хотя бы один решённый участник.
     */
    private fun skladchinaWindow(clubId: UUID, start: OffsetDateTime, end: OffsetDateTime): WindowValue {
        val settled = listOf(
            SkladchinaParticipantStatus.paid,
            SkladchinaParticipantStatus.declined,
            SkladchinaParticipantStatus.expired_no_response,
        )
        val record = dsl.select(
            DSL.count().filterWhere(SKLADCHINA_PARTICIPANTS.STATUS.eq(SkladchinaParticipantStatus.paid)),
            DSL.count().filterWhere(SKLADCHINA_PARTICIPANTS.STATUS.`in`(settled)),
        )
            .from(SKLADCHINA_PARTICIPANTS)
            .join(SKLADCHINAS).on(SKLADCHINAS.ID.eq(SKLADCHINA_PARTICIPANTS.SKLADCHINA_ID))
            .where(
                SKLADCHINAS.CLUB_ID.eq(clubId)
                    .and(SKLADCHINAS.CLOSED_AT.ge(start))
                    .and(SKLADCHINAS.CLOSED_AT.lt(end)),
            )
            .fetchOne()
        val paid = record?.value1() ?: 0
        val total = record?.value2() ?: 0
        val value = if (total > 0) (paid.toDouble() / total * 100).roundToInt().coerceIn(0, 100) else 0
        return WindowValue(value, total > 0)
    }

    /** Возвращает пару (число pending-заявок, из них «зависшие» старше 24 часов). */
    private fun pendingApplications(clubId: UUID, staleBefore: OffsetDateTime): Pair<Int, Int> {
        val record = dsl.select(
            DSL.count(),
            DSL.count().filterWhere(APPLICATIONS.CREATED_AT.lt(staleBefore)),
        )
            .from(APPLICATIONS)
            .where(APPLICATIONS.CLUB_ID.eq(clubId).and(APPLICATIONS.STATUS.eq(ApplicationStatus.pending)))
            .fetchOne()
        return (record?.value1() ?: 0) to (record?.value2() ?: 0)
    }

    private fun autoRejectedCount(clubId: UUID, start: OffsetDateTime): Int =
        dsl.selectCount().from(APPLICATIONS)
            .where(
                APPLICATIONS.CLUB_ID.eq(clubId)
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.auto_rejected))
                    .and(APPLICATIONS.RESOLVED_AT.ge(start)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Споры по явке, когда-либо поданные против отметок клуба (накопительно, за всё время).
     * `attendance = disputed` — состояние временное: решённый/протухший спор возвращается в
     * attended/absent и сохраняется только как `dispute_terminal = true` (плюс записанный
     * `dispute_note`), поэтому счёт по одному `disputed` показывал бы ~0 после финализации каждого
     * события. Объединяем живое состояние с обоими постоянными маркерами, чтобы посчитать реальный
     * track record споров организатора. Сигнал — «участник поднял спор», независимо от того, кто
     * выиграл разбор (§9.5; дизайн §2 «споры (member-raised)»).
     */
    private fun disputeCount(clubId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(
                        EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed)
                            .or(EVENT_RESPONSES.DISPUTE_TERMINAL.isTrue)
                            .or(EVENT_RESPONSES.DISPUTE_NOTE.isNotNull),
                    ),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** Проведённые (прошедшие, неотменённые) события за всё время — знаменатель «N из M» для споров. */
    private fun totalMeetings(clubId: UUID, now: OffsetDateTime): Int =
        dsl.selectCount().from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    private fun cancelledMeetings(clubId: UUID, start: OffsetDateTime): Int =
        dsl.selectCount().from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.eq(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.ge(start)),
            )
            .fetchOne(0, Int::class.java) ?: 0
}
