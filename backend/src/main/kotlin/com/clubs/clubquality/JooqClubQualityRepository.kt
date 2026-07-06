package com.clubs.clubquality

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.SKLADCHINAS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.Period
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Read-only агрегации поверх существующих таблиц (`clubs`, `events`, `event_responses`, `skladchinas`).
 * Своей схемы нет. Чтение этих общих jOOQ-таблиц согласуется с
 * [com.clubs.reputation.JooqReputationRepository], который уже агрегирует events/responses.
 *
 * Временные окна вычисляются в Kotlin и передаются параметрами (без SQL `interval`) — детерминированно
 * и тестируемо. «Состоявшаяся» = прошедшая неотменённая встреча.
 */
@Repository
class JooqClubQualityRepository(private val dsl: DSLContext) : ClubQualityRepository {

    private companion object {
        // Окно (дней) для метрик «частота встреч» и «обычно приходит»
        const val WINDOW_DAYS = 90L
        // Длина того же окна в месяцах — знаменатель метрики «встреч в месяц»
        const val MONTHS_IN_WINDOW = 3.0
        // Минимум посещённых встреч, чтобы участник считался частью «основы клуба»
        const val CORE_ATTENDANCE_THRESHOLD = 3
    }

    override fun findClubFacts(clubId: UUID): ClubFacts? {
        val createdAt = dsl.select(CLUBS.CREATED_AT)
            .from(CLUBS)
            .where(CLUBS.ID.eq(clubId))
            .fetchOne(CLUBS.CREATED_AT)
            ?: return null

        val now = OffsetDateTime.now()
        val windowStart = now.minusDays(WINDOW_DAYS)

        return ClubFacts(
            meetingsPerMonth = meetingsPerMonth(clubId, now, windowStart),
            avgAttendance = avgAttendance(clubId, now, windowStart),
            coreSize = coreSize(clubId),
            ageMonths = Period.between(createdAt.toLocalDate(), now.toLocalDate())
                .toTotalMonths().toInt().coerceAtLeast(0),
            totalMeetings = totalMeetings(clubId, now),
            successfulSkladchinas = successfulSkladchinas(clubId),
        )
    }

    private fun heldInWindow(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime) =
        EVENTS.CLUB_ID.eq(clubId)
            .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            .and(EVENTS.EVENT_DATETIME.lt(now))
            .and(EVENTS.EVENT_DATETIME.ge(windowStart))

    private fun meetingsPerMonth(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime): Double {
        val held = dsl.selectCount()
            .from(EVENTS)
            .where(heldInWindow(clubId, now, windowStart))
            .fetchOne(0, Int::class.java) ?: 0
        return (held / MONTHS_IN_WINDOW * 10.0).roundToInt() / 10.0
    }

    /**
     * Σ ответов attended ÷ число финализированных встреч в 90-дневном окне. Встреча, на которую
     * никто не пришёл, всё равно входит в знаменатель (честно занижает среднее). 0, если
     * финализированных встреч нет.
     */
    private fun avgAttendance(clubId: UUID, now: OffsetDateTime, windowStart: OffsetDateTime): Int {
        val record = dsl.select(
            DSL.count().filterWhere(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended)),
            DSL.countDistinct(EVENTS.ID),
        )
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(heldInWindow(clubId, now, windowStart).and(EVENTS.ATTENDANCE_FINALIZED.isTrue))
            .fetchOne()

        val attended = record?.value1() ?: 0
        val finalizedMeetings = record?.value2() ?: 0
        return if (finalizedMeetings > 0) {
            (attended.toDouble() / finalizedMeetings).roundToInt()
        } else {
            0
        }
    }

    /**
     * Distinct-пользователи БЕЗ владельца с ≥3 посещёнными встречами клуба, которые ВСЁ ЕЩЁ участники
     * (стабильное ядро — «основа клуба»). Организатор исключён: он сам отмечает собственную
     * посещаемость, поэтому его учёт раздувает ядро (оно никогда не опустилось бы ниже 1) и смешивает
     * организатора с ядром участников. Исключение владельца соответствует правилу L3 «Сплочённость»
     * (gamification §2).
     *
     * Join по текущему membership: пользователь, ушедший или удалённый (статус `cancelled`), больше
     * не входит в ядро, так что «основа клуба» падает при выходе/кике. `frozen` и `expired`
     * по-прежнему считаются: это пауза доступа (первый взнос / просрочка продления), а не уход, и
     * ядро не должно мигать каждый раз, когда платёжное окно участника ненадолго истекает.
     */
    private fun coreSize(clubId: UUID): Int =
        dsl.select(EVENT_RESPONSES.USER_ID)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .join(MEMBERSHIPS).on(
                MEMBERSHIPS.USER_ID.eq(EVENT_RESPONSES.USER_ID)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.expired, MembershipStatus.frozen)),
            )
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
                    .and(EVENT_RESPONSES.USER_ID.ne(DSL.select(CLUBS.OWNER_ID).from(CLUBS).where(CLUBS.ID.eq(clubId)))),
            )
            .groupBy(EVENT_RESPONSES.USER_ID)
            .having(DSL.count().ge(CORE_ATTENDANCE_THRESHOLD))
            .fetch()
            .size

    /** Состоявшиеся (прошедшие, неотменённые) события клуба за всё время. */
    private fun totalMeetings(clubId: UUID, now: OffsetDateTime): Int =
        dsl.selectCount()
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lt(now)),
            )
            .fetchOne(0, Int::class.java) ?: 0

    /** Складчины клуба, закрытые успешно (майлстоун «первый сбор»). */
    private fun successfulSkladchinas(clubId: UUID): Int =
        dsl.selectCount()
            .from(SKLADCHINAS)
            .where(SKLADCHINAS.CLUB_ID.eq(clubId).and(SKLADCHINAS.STATUS.eq(SkladchinaStatus.closed_success)))
            .fetchOne(0, Int::class.java) ?: 0

    // ---- Батч (Discovery-карточка): возраст · вовлечённость, один сгруппированный запрос на метрику (без N+1) ----

    override fun findClubCardFacts(clubIds: Collection<UUID>): List<ClubCardFacts> {
        if (clubIds.isEmpty()) return emptyList()
        val ids = clubIds.toSet()
        val now = OffsetDateTime.now()
        val windowStart = now.minusDays(WINDOW_DAYS)

        val createdAt = createdAtByClub(ids)
        if (createdAt.isEmpty()) return emptyList()

        val responders = recentRespondersByClub(ids, windowStart)
        val aliveMembers = aliveMemberCountByClub(ids)

        return createdAt.map { (clubId, created) ->
            val alive = aliveMembers[clubId] ?: 0
            val responded = responders[clubId] ?: 0
            ClubCardFacts(
                clubId = clubId,
                ageDays = ChronoUnit.DAYS.between(created.toLocalDate(), now.toLocalDate())
                    .toInt().coerceAtLeast(0),
                engagementPercent = if (alive > 0) {
                    (responded.toDouble() / alive * 100).roundToInt().coerceIn(0, 100)
                } else {
                    0
                },
            )
        }
    }

    /** Существующие клубы среди [ids] → created_at. Ids без строки клуба отсутствуют (пропускаются). */
    private fun createdAtByClub(ids: Set<UUID>): Map<UUID, OffsetDateTime> =
        dsl.select(CLUBS.ID, CLUBS.CREATED_AT)
            .from(CLUBS)
            .where(CLUBS.ID.`in`(ids))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    /**
     * Distinct-участники, откликнувшиеся на недавние (в окне или предстоящие) неотменённые события клуба.
     * Сигнал, идущий от участников (голосование/going) — числитель вовлечённости.
     */
    private fun recentRespondersByClub(ids: Set<UUID>, windowStart: OffsetDateTime): Map<UUID, Int> =
        dsl.select(EVENTS.CLUB_ID, DSL.countDistinct(EVENT_RESPONSES.USER_ID))
            .from(EVENTS)
            .join(EVENT_RESPONSES).on(EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID))
            .where(
                EVENTS.CLUB_ID.`in`(ids)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.ge(windowStart)),
            )
            .groupBy(EVENTS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

    /** Живые membership'ы с доступом (active) на клуб — знаменатель вовлечённости. frozen/expired
     *  исключены: без доступа участвовать в событиях нельзя, их учёт разбавлял бы вовлечённость. */
    private fun aliveMemberCountByClub(ids: Set<UUID>): Map<UUID, Int> =
        dsl.select(MEMBERSHIPS.CLUB_ID, DSL.count())
            .from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(ids)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)),
            )
            .groupBy(MEMBERSHIPS.CLUB_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
}
