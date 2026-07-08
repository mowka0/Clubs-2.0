package com.clubs.event

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.membership.MembershipAccess
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventResponseRepository(
    private val dsl: DSLContext,
    private val mapper: EventResponseMapper
) : EventResponseRepository {

    override fun upsertStage1Vote(eventId: UUID, userId: UUID, vote: Stage_1Vote): EventResponse {
        val existing = dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .fetchOne()

        val record = if (existing != null) {
            dsl.update(EVENT_RESPONSES)
                .set(EVENT_RESPONSES.STAGE_1_VOTE, vote)
                .set(EVENT_RESPONSES.STAGE_1_TIMESTAMP, OffsetDateTime.now())
                .set(EVENT_RESPONSES.UPDATED_AT, OffsetDateTime.now())
                .where(EVENT_RESPONSES.ID.eq(existing.id))
                .returning()
                .fetchOne()!!
        } else {
            dsl.insertInto(EVENT_RESPONSES)
                .set(EVENT_RESPONSES.EVENT_ID, eventId)
                .set(EVENT_RESPONSES.USER_ID, userId)
                .set(EVENT_RESPONSES.STAGE_1_VOTE, vote)
                .set(EVENT_RESPONSES.STAGE_1_TIMESTAMP, OffsetDateTime.now())
                .returning()
                .fetchOne()!!
        }
        return mapper.toDomain(record)
    }

    override fun createLateStage2Entry(eventId: UUID, userId: UUID): EventResponse {
        val record = dsl.insertInto(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.EVENT_ID, eventId)
            .set(EVENT_RESPONSES.USER_ID, userId)
            // stage_1_vote / stage_1_timestamp остаются NULL — участник не голосовал на Этапе 1.
            // Позицию в очереди задаёт stage_2_timestamp, который проставит следующий за этой вставкой
            // updateStage2Vote (в той же транзакции под slot-lock), поэтому метка Этапа 1 не нужна.
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findByEventAndUser(eventId: UUID, userId: UUID): EventResponse? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun countByVote(eventId: UUID): Map<String, Int> {
        val going = countByStage1Vote(eventId, Stage_1Vote.going)
        val maybe = countByStage1Vote(eventId, Stage_1Vote.maybe)
        val notGoing = countByStage1Vote(eventId, Stage_1Vote.not_going)
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing)
    }

    override fun countConfirmed(eventId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun countWaitlisted(eventId: UUID): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.waitlisted))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun lockEventSlots(eventId: UUID) {
        // Лок в рамках транзакции: снимается автоматически при commit/rollback, поэтому unlock не
        // нужен и не течёт при исключении. hashtext по префиксованному ключу — тот же паттерн, что
        // в JooqReputationRepository.recompute; префикс отделяет это пространство ключей от локов recompute.
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", "event-slots:$eventId")
    }

    override fun findFirstWaitlisted(eventId: UUID): EventResponse? =
        dsl.selectFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.waitlisted))
            )
            // Очередь — по времени вставания в лист ожидания на ЭТАПЕ 2 (stage_2_timestamp
            // проставляется при updateStage2Vote(waitlisted)), НЕ по голосу Этапа 1. Кто раньше
            // подтвердил при полном зале — тот выше. Waitlisted всегда имеет stage_2_timestamp.
            .orderBy(EVENT_RESPONSES.STAGE_2_TIMESTAMP.asc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun updateStage2Vote(id: UUID, vote: Stage_2Vote, finalStatus: FinalStatus): EventResponse {
        val record = dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.STAGE_2_VOTE, vote)
            .set(EVENT_RESPONSES.STAGE_2_TIMESTAMP, OffsetDateTime.now())
            .set(EVENT_RESPONSES.FINAL_STATUS, finalStatus)
            .set(EVENT_RESPONSES.UPDATED_AT, OffsetDateTime.now())
            .where(EVENT_RESPONSES.ID.eq(id))
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun expireUnconfirmedForStartedEvents(now: OffsetDateTime): Int {
        // Начавшиеся события с запущенным stage-2, не отменённые. Независимость от статуса —
        // намеренная: EventCompletionService переводит stage_2 -> completed после 6ч grace-периода,
        // поэтому фильтр по status = stage_2 пропустил бы неподтверждённых на событиях старше grace.
        val startedTriggeredEventIds = dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.STAGE_2_TRIGGERED.isTrue
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(now))
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.STAGE_2_VOTE, Stage_2Vote.expired_no_confirm)
            .set(EVENT_RESPONSES.FINAL_STATUS, FinalStatus.expired_no_confirm)
            .set(EVENT_RESPONSES.STAGE_2_TIMESTAMP, now)
            .set(EVENT_RESPONSES.UPDATED_AT, now)
            .where(
                EVENT_RESPONSES.STAGE_2_VOTE.isNull
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(startedTriggeredEventIds))
            )
            .execute()
    }

    override fun findRespondersWithUsers(eventId: UUID): List<EventResponderInfo> =
        dsl.select(
            EVENT_RESPONSES.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            EVENT_RESPONSES.STAGE_1_VOTE,
            EVENT_RESPONSES.FINAL_STATUS,
            EVENT_RESPONSES.ATTENDANCE,
            EVENT_RESPONSES.DISPUTE_NOTE
        )
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    // Голосовавшие на Этапе 1 ИЛИ поздние участники со статусом Этапа 2
                    // (у не голосовавших stage_1_vote=NULL, но появляется final_status — их нельзя терять).
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.isNotNull.or(EVENT_RESPONSES.FINAL_STATUS.isNotNull))
            )
            // Первичный ключ — stage_2_timestamp ASC (NULLS LAST): лист ожидания на странице события
            // идёт РОВНО в порядке приоритета продвижения (findFirstWaitlisted, тоже по stage_2). Для
            // предварительного списка Этапа 1 (действий Этапа 2 ещё нет, stage_2_timestamp=NULL у всех)
            // NULLS LAST + вторичный stage_1_timestamp сохраняет порядок «кто раньше откликнулся».
            .orderBy(EVENT_RESPONSES.STAGE_2_TIMESTAMP.asc().nullsLast(), EVENT_RESPONSES.STAGE_1_TIMESTAMP.asc())
            .fetch { r ->
                EventResponderInfo(
                    userId = r.get(EVENT_RESPONSES.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME)!!,
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    stage1Vote = r.get(EVENT_RESPONSES.STAGE_1_VOTE),
                    finalStatus = r.get(EVENT_RESPONSES.FINAL_STATUS),
                    attendance = r.get(EVENT_RESPONSES.ATTENDANCE),
                    disputeNote = r.get(EVENT_RESPONSES.DISPUTE_NOTE)
                )
            }

    override fun findStage2TargetTelegramIds(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun findStage2InviteTelegramIds(eventId: UUID): List<Long> =
        // Аудитория приглашения на Этап 2 строится от УЧАСТНИКОВ КЛУБА с доступом (не от голосов),
        // чтобы включить не ответивших на Этапе 1. LEFT JOIN на ответы: у не ответившего строки нет
        // (stage_1_vote читается как NULL). IS DISTINCT FROM 'not_going' истинно для NULL и для
        // going/maybe → включаем всех, КРОМЕ проголосовавших not_going.
        dsl.selectDistinct(USERS.TELEGRAM_ID)
            .from(EVENTS)
            .join(MEMBERSHIPS).on(MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID).and(MembershipAccess.hasAccess()))
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID).and(EVENT_RESPONSES.USER_ID.eq(MEMBERSHIPS.USER_ID))
            )
            .where(
                EVENTS.ID.eq(eventId)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.isDistinctFrom(Stage_1Vote.not_going))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    override fun findTelegramIdsByEventAndUserIds(eventId: UUID, userIds: List<UUID>): List<Long> {
        if (userIds.isEmpty()) return emptyList()
        return dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.`in`(userIds))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }

    override fun setAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int {
        val target = if (attended) AttendanceStatus.attended else AttendanceStatus.absent
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, target)
            // F5-16: свежая отметка организатора заново открывает право на спор (участник может
            // оспорить НОВУЮ отметку). Идемпотентные повторы затрагивают 0 строк (IS DISTINCT FROM
            // target ниже), так что уже разрешённая/терминальная строка не переоткрывается
            // повторной no-op отметкой.
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, false)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    // Отмечать можно только финальный ростер (PRD §4.4.3). Проголосовавший
                    // going/maybe, но так и не подтвердивший — не в нём; репутация всё равно
                    // игнорирует неподтверждённые строки, так что их отметка была no-op'ом,
                    // только засорявшим UI.
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
                    // F5-15(1): никогда не перезаписывать активный спор — только resolveDispute
                    // может изменить строку в статусе `disputed`. IS DISTINCT FROM, а не <>: у первой
                    // отметки attendance=NULL, и `NULL <> 'disputed'` даёт NULL (строка молча
                    // пропускается → ломается happy-path).
                    .and(EVENT_RESPONSES.ATTENDANCE.isDistinctFrom(AttendanceStatus.disputed))
                    // Только настоящие переходы: идемпотентный повтор (строка уже в целевом
                    // состоянии) затрагивает 0 строк. Поэтому markedCount считает реальные изменения,
                    // а `updated > 0 && !attended` означает ровно "стал отсутствующим только что" для
                    // DM (F5-15.2) без чтения предыдущего состояния.
                    .and(EVENT_RESPONSES.ATTENDANCE.isDistinctFrom(target))
                    // F5-09: никогда не писать посещаемость по уже финализированному событию
                    // (TOCTOU с финализатором). Подзапрос по EVENTS — event_responses.attendance_finalized
                    // мёртвая колонка (никогда не пишется); актуальный флаг живёт в events.
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(notFinalizedEvent(eventId)))
            )
            .execute()
    }

    override fun disputeAbsentAttendance(eventId: UUID, userId: UUID, note: String?): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.disputed)
            .set(EVENT_RESPONSES.DISPUTE_NOTE, note)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.absent))
                    // F5-16: разрешённую/терминальную отметку нельзя оспорить повторно (пинг-понг).
                    // У endpoint'а спора нет авторизации кроме JWT, поэтому эта проверка на уровне
                    // БД — не просто UI — несёт реальную нагрузку.
                    .and(EVENT_RESPONSES.DISPUTE_TERMINAL.isFalse)
                    // F5-10 (порядок A): если финализатор уже закоммитил attendance_finalized=true,
                    // окно закрыто — отказать (без лишней строки disputed, без ложной DM организатору).
                    // ATT-2 (resolveExpiredDisputesToAbsent) остаётся подстраховкой для порядка B
                    // (спор коммитится раньше ATT-2 в той же транзакции финализации) — НЕ удалять.
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(notFinalizedEvent(eventId)))
            )
            .execute()

    override fun resolveDisputedAttendance(eventId: UUID, userId: UUID, attended: Boolean): Int =
        dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, if (attended) AttendanceStatus.attended else AttendanceStatus.absent)
            // F5-16: организатор вынес решение — отметка терминальна, повторный спор невозможен.
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, true)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
            )
            .execute()

    override fun resolveExpiredDisputesToAbsent(eventIds: List<UUID>): Int {
        if (eventIds.isEmpty()) return 0
        return dsl.update(EVENT_RESPONSES)
            .set(EVENT_RESPONSES.ATTENDANCE, AttendanceStatus.absent)
            // F5-16: окно истекло, спор не разрешён — отметка тоже становится терминальной
            // (симметрия аудита; attendance_finalized уже блокирует повторный спор, это доп. подстраховка).
            .set(EVENT_RESPONSES.DISPUTE_TERMINAL, true)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.disputed))
                    // Только финальный (confirmed) ростер попадает в репутацию. Спорная отметка
                    // сегодня может существовать только на confirmed-строке (setAttendance это
                    // гарантирует), но проверка добавлена и здесь — чтобы неподтверждённая спорная
                    // строка (порча данных / будущие изменения) никогда молча не мутировала, пока
                    // леджер её игнорирует. Зеркалит setAttendance.
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
            )
            .execute()
    }

    // F5-09 / F5-10: "это событие ещё не финализировано" в виде подзапроса по EVENTS, для
    // использования внутри UPDATE'ов EVENT_RESPONSES. event_responses.attendance_finalized —
    // мёртвая колонка (никогда не пишется); авторитетный флаг — events.attendance_finalized,
    // выставляется финализатором.
    private fun notFinalizedEvent(eventId: UUID) =
        DSL.select(EVENTS.ID).from(EVENTS)
            .where(EVENTS.ID.eq(eventId).and(EVENTS.ATTENDANCE_FINALIZED.isFalse))

    override fun findConfirmedActiveEventObligations(userId: UUID, clubId: UUID): List<EventObligation> =
        dsl.select(EVENTS.ID, EVENTS.EVENT_DATETIME)
            .from(EVENT_RESPONSES)
            .join(EVENTS).on(EVENTS.ID.eq(EVENT_RESPONSES.EVENT_ID))
            .where(
                EVENT_RESPONSES.USER_ID.eq(userId)
                    .and(EVENTS.CLUB_ID.eq(clubId))
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // Итог финализированного события принадлежит пайплайну посещаемости — никогда
                    // не перезаписывать реальную посещаемость выходным no_show.
                    .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
            )
            .fetch { r -> EventObligation(r.get(EVENTS.ID)!!, r.get(EVENTS.EVENT_DATETIME)!!) }

    override fun promoteFirstWaitlisted(eventId: UUID): UUID? {
        val first = findFirstWaitlisted(eventId) ?: return null
        updateStage2Vote(first.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
        return first.userId
    }

    override fun deleteByUserAndClubAndActiveEvents(userId: UUID, clubId: UUID): Int {
        val activeEventIds = dsl.select(EVENTS.ID)
            .from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // Исключить события с финализированной посещаемостью (достижимо пока статус ещё
                    // stage_2 — финализация меняет флаг, отдельный проход позже меняет статус). Их
                    // РЕАЛЬНЫЙ исход посещаемости принадлежит пайплайну репутации; удаление
                    // подтверждённой строки здесь стёрло бы ещё не обработанный no_show. Тот же
                    // scope, что использует перечисление при выходе (findConfirmedActiveEventObligations
                    // тоже исключает финализированные).
                    .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)
            )
        return dsl.deleteFrom(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.USER_ID.eq(userId)
                    .and(EVENT_RESPONSES.EVENT_ID.`in`(activeEventIds))
            )
            .execute()
    }

    override fun findAttendedUserIds(eventId: UUID): List<UUID> =
        dsl.select(EVENT_RESPONSES.USER_ID)
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
            )
            .fetch()
            .mapNotNull { it.value1() }

    override fun findFirstTimeAttendeeFirstNames(eventId: UUID, clubId: UUID): List<String> {
        // «Другие attended-строки в клубе»: коррелированный NOT EXISTS по событиям того же клуба.
        val other = EVENT_RESPONSES.`as`("other_responses")
        val otherEvents = EVENTS.`as`("other_events")
        return dsl.select(USERS.FIRST_NAME)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended))
                    .andNotExists(
                        dsl.selectOne()
                            .from(other)
                            .join(otherEvents).on(otherEvents.ID.eq(other.EVENT_ID))
                            .where(
                                other.USER_ID.eq(EVENT_RESPONSES.USER_ID)
                                    .and(other.EVENT_ID.ne(eventId))
                                    .and(other.ATTENDANCE.eq(AttendanceStatus.attended))
                                    .and(otherEvents.CLUB_ID.eq(clubId))
                            )
                    )
            )
            .orderBy(USERS.FIRST_NAME.asc())
            .fetch()
            .mapNotNull { it.value1() }
    }

    private fun countByStage1Vote(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0
}
