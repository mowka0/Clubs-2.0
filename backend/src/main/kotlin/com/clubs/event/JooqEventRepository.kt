package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.EVENTS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.membership.MembershipAccess
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqEventRepository(
    private val dsl: DSLContext,
    private val mapper: EventMapper
) : EventRepository {

    override fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event {
        val record = dsl.insertInto(EVENTS)
            .set(EVENTS.ID, UUID.randomUUID())
            .set(EVENTS.CLUB_ID, clubId)
            .set(EVENTS.CREATED_BY, createdBy)
            .set(EVENTS.TITLE, request.title)
            .set(EVENTS.DESCRIPTION, request.description)
            .set(EVENTS.LOCATION_TEXT, request.locationText)
            .set(EVENTS.LOCATION_LAT, request.locationLat)
            .set(EVENTS.LOCATION_LON, request.locationLon)
            .set(EVENTS.LOCATION_HINT, request.locationHint)
            .set(EVENTS.EVENT_DATETIME, request.eventDatetime)
            .set(EVENTS.PARTICIPANT_LIMIT, request.participantLimit)
            .set(EVENTS.VOTING_OPENS_DAYS_BEFORE, request.votingOpensDaysBefore)
            .set(EVENTS.STAGE2_LEAD_MINUTES, request.stage2LeadMinutes)
            .set(EVENTS.STATUS, EventStatus.upcoming)
            .set(EVENTS.STAGE_2_TRIGGERED, false)
            .set(EVENTS.ATTENDANCE_MARKED, false)
            .set(EVENTS.ATTENDANCE_FINALIZED, false)
            .set(EVENTS.PHOTO_URL, request.photoUrl)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Event? =
        dsl.selectFrom(EVENTS)
            .where(EVENTS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto> {
        var condition = EVENTS.CLUB_ID.eq(clubId)
        status?.let { condition = condition.and(EVENTS.STATUS.eq(it)) }

        val total = dsl.selectCount().from(EVENTS).where(condition)
            .fetchOne(0, Long::class.java) ?: 0L

        val events = dsl.selectFrom(EVENTS)
            .where(condition)
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(size)
            .offset(page * size)
            .fetch()
            .map(mapper::toDomain)

        val eventIds = events.map { it.id }
        val goingCounts = fetchGoingCounts(eventIds)

        val items = events.map { event -> mapper.toListItemDto(event, goingCounts[event.id] ?: 0) }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    override fun findAllByClubWithGoingCount(clubId: UUID): List<EventWithGoingCount> {
        val events = dsl.selectFrom(EVENTS)
            .where(EVENTS.CLUB_ID.eq(clubId))
            .orderBy(EVENTS.CREATED_AT.desc(), EVENTS.ID.asc())
            .fetch()
            .map(mapper::toDomain)

        if (events.isEmpty()) return emptyList()

        val eventIds = events.map { it.id }
        val goingCounts = fetchGoingCounts(eventIds)
        val confirmedCounts = fetchConfirmedCounts(eventIds)
        return events.map {
            EventWithGoingCount(
                event = it,
                goingCount = goingCounts[it.id] ?: 0,
                confirmedCount = confirmedCounts[it.id] ?: 0
            )
        }
    }

    override fun findActionRequiredEventIds(clubId: UUID, userId: UUID, now: OffsetDateTime): Set<UUID> {
        // Голос Stage-1 ещё не отдан: окно голосования открыто (event_datetime - voting_opens_days_before <= now),
        // статус всё ещё upcoming, этот пользователь ещё не голосовал.
        val stage1Pending = EVENTS.STATUS.eq(EventStatus.upcoming)
            .and(EVENT_RESPONSES.STAGE_1_VOTE.isNull)
            .and(
                DSL.condition(
                    "{0} - ({1} * INTERVAL '1 day') <= {2}",
                    EVENTS.EVENT_DATETIME,
                    EVENTS.VOTING_OPENS_DAYS_BEFORE,
                    DSL.value(now)
                )
            )
        // Подтверждение Stage-2 ещё не отдано: проголосовал going/maybe на stage 1, но ещё не подтвердил/отказался.
        val stage2Pending = EVENTS.STATUS.eq(EventStatus.stage_2)
            .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
            .and(EVENT_RESPONSES.STAGE_2_VOTE.isNull)

        return dsl.select(EVENTS.ID)
            .from(EVENTS)
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID)
                    .and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .where(EVENTS.CLUB_ID.eq(clubId))
            .and(stage1Pending.or(stage2Pending))
            .fetch(EVENTS.ID)
            .filterNotNull()
            .toSet()
    }

    override fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MyFeedItem> {
        val now = OffsetDateTime.now()

        // Лента — два набора, объединяемых по events.id:
        // (A) ПРЕДСТОЯЩИЕ — без изменений семантики: активный клуб, активное членство
        //     (MembershipAccess), статус upcoming/stage_2, event_datetime > now.
        // (B) ИСТОРИЯ (новое): личная отметка явки (attendance='attended'), клуб активен,
        //     событие не отменено, event_datetime <= now. JOIN с membership НЕ обязателен —
        //     история переживает выход/исключение из клуба (Решение 2в спеки): строка выдаётся
        //     строго по event_responses.user_id = :userId, это собственный факт пользователя,
        //     не контент клуба.
        // Дизъюнктность наборов — ПО ПОСТРОЕНИЮ через event_datetime (A: > now, B: <= now), а не
        // через инвариант AttendanceService (отметка явки запрещена до старта): иначе появление
        // переноса события дало бы экс-участнику будущее событие клуба, из которого он исключён.
        val upcomingPredicate = EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_2)
            .and(EVENTS.EVENT_DATETIME.gt(now))
            .and(MembershipAccess.hasAccess())
        val historyPredicate = EVENT_RESPONSES.ATTENDANCE.eq(AttendanceStatus.attended)
            .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            .and(EVENTS.EVENT_DATETIME.le(now))
        // is_active = true применяется к ОБЕИМ половинам: soft-deleted клуб исключён и из истории (AC-H7).
        val baseCondition = CLUBS.IS_ACTIVE.eq(true)
            .and(upcomingPredicate.or(historyPredicate))

        // Джойны LEFT: membership может отсутствовать (пользователь вышел, но история жива),
        // event_responses — собственный отклик вызывающего. user_id заведён в ON, а не в WHERE:
        // в WHERE он деградировал бы LEFT до INNER и убил историю без активного членства.
        val total = dsl.select(DSL.countDistinct(EVENTS.ID))
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .leftJoin(MEMBERSHIPS).on(
                MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID).and(MEMBERSHIPS.USER_ID.eq(userId))
            )
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID).and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .where(baseCondition)
            .fetchOne(0, Long::class.java) ?: 0L

        // Бакет истории: 0 = предстоящее (набор A), 1 = история (набор B). Наборы дизъюнктны,
        // поэтому «не A» ⇒ история.
        val historyBucket = DSL.case_()
            .`when`(upcomingPredicate, 0)
            .otherwise(1)

        // Внутри предстоящих: события, требующие действия, — сверху. Окно голосования открывается
        // в момент event_datetime - voting_opens_days_before * 1 day. Для истории значение не важно
        // (её отделяет уже historyBucket).
        val actionRequiredOrder = DSL.case_()
            .`when`(
                EVENTS.STATUS.eq(EventStatus.upcoming)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.isNull)
                    .and(
                        DSL.condition(
                            "{0} - ({1} * INTERVAL '1 day') <= {2}",
                            EVENTS.EVENT_DATETIME,
                            EVENTS.VOTING_OPENS_DAYS_BEFORE,
                            DSL.value(now)
                        )
                    ),
                1
            )
            .`when`(
                EVENTS.STATUS.eq(EventStatus.stage_2)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.`in`(Stage_1Vote.going, Stage_1Vote.maybe))
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.isNull),
                1
            )
            .otherwise(0)

        // Условный ключ сортировки предстоящих: event_datetime для набора A, NULL для истории
        // (implicit ELSE NULL). У истории порядок задаёт последний ключ event_datetime DESC.
        val upcomingSort = DSL.`when`(upcomingPredicate, EVENTS.EVENT_DATETIME)

        val rows = dsl.select(
            EVENTS.ID,
            EVENTS.CLUB_ID,
            EVENTS.CREATED_BY,
            EVENTS.TITLE,
            EVENTS.DESCRIPTION,
            EVENTS.LOCATION_TEXT,
            EVENTS.EVENT_DATETIME,
            EVENTS.PARTICIPANT_LIMIT,
            EVENTS.VOTING_OPENS_DAYS_BEFORE,
            EVENTS.STATUS,
            EVENTS.STAGE_2_TRIGGERED,
            EVENTS.ATTENDANCE_MARKED,
            EVENTS.ATTENDANCE_FINALIZED,
            EVENTS.PHOTO_URL,
            EVENTS.CREATED_AT,
            EVENTS.UPDATED_AT,
            CLUBS.NAME.`as`("club_name"),
            CLUBS.AVATAR_URL.`as`("club_avatar_url"),
            EVENT_RESPONSES.STAGE_1_VOTE.`as`("my_vote"),
            EVENT_RESPONSES.FINAL_STATUS.`as`("my_final_status"),
            historyBucket.`as`("history_bucket"),
        )
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .leftJoin(MEMBERSHIPS).on(
                MEMBERSHIPS.CLUB_ID.eq(EVENTS.CLUB_ID).and(MEMBERSHIPS.USER_ID.eq(userId))
            )
            .leftJoin(EVENT_RESPONSES).on(
                EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID).and(EVENT_RESPONSES.USER_ID.eq(userId))
            )
            .where(baseCondition)
            .orderBy(
                historyBucket.asc(),              // предстоящие (0) → история (1)
                actionRequiredOrder.desc(),       // внутри предстоящих — action-required сверху
                upcomingSort.asc().nullsLast(),   // предстоящие: ближайшее первым; история → NULL → в конец
                EVENTS.EVENT_DATETIME.desc(),     // история: недавние первыми
                EVENTS.ID.asc()                   // детерминированный тай-брейк: ни один ключ выше не уникален,
                                                  // без него offset-пагинация могла бы менять местами
                                                  // события с одинаковым datetime между страницами
            )
            .limit(size)
            .offset(page * size)
            .fetch()

        val eventIds = rows.map { it.get(EVENTS.ID)!! }
        val goingCounts = fetchGoingCounts(eventIds)
        val confirmedCounts = fetchConfirmedCounts(eventIds)

        val items = rows.map { r ->
            val eventId = r.get(EVENTS.ID)!!
            val event = Event(
                id = eventId,
                clubId = r.get(EVENTS.CLUB_ID)!!,
                createdBy = r.get(EVENTS.CREATED_BY)!!,
                title = r.get(EVENTS.TITLE)!!,
                description = r.get(EVENTS.DESCRIPTION),
                locationText = r.get(EVENTS.LOCATION_TEXT),
                eventDatetime = r.get(EVENTS.EVENT_DATETIME)!!,
                participantLimit = r.get(EVENTS.PARTICIPANT_LIMIT),
                votingOpensDaysBefore = r.get(EVENTS.VOTING_OPENS_DAYS_BEFORE) ?: EventMapper.DEFAULT_VOTING_OPENS_DAYS_BEFORE,
                status = r.get(EVENTS.STATUS) ?: EventStatus.upcoming,
                stage2Triggered = r.get(EVENTS.STAGE_2_TRIGGERED) ?: false,
                attendanceMarked = r.get(EVENTS.ATTENDANCE_MARKED) ?: false,
                attendanceFinalized = r.get(EVENTS.ATTENDANCE_FINALIZED) ?: false,
                photoUrl = r.get(EVENTS.PHOTO_URL),
                createdAt = r.get(EVENTS.CREATED_AT),
                updatedAt = r.get(EVENTS.UPDATED_AT)
            )
            MyFeedItem(
                event = event,
                clubName = r.get("club_name", String::class.java),
                clubAvatarUrl = r.get("club_avatar_url", String::class.java),
                myVote = r.get("my_vote", Stage_1Vote::class.java),
                myFinalStatus = r.get("my_final_status", FinalStatus::class.java),
                goingCount = goingCounts[eventId] ?: 0,
                confirmedCount = confirmedCounts[eventId] ?: 0,
                // Бакет 1 ⇒ строка из набора истории (посещённое прошедшее событие).
                isHistory = r.get("history_bucket", Int::class.java) == 1
            )
        }

        val totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content = items, totalElements = total, totalPages = totalPages, page = page, size = size)
    }

    override fun getVoteCounts(eventId: UUID): Map<String, Int> {
        val going = countVotes(eventId, Stage_1Vote.going)
        val maybe = countVotes(eventId, Stage_1Vote.maybe)
        val notGoing = countVotes(eventId, Stage_1Vote.not_going)
        // Размер подтверждённого ростера Stage-2. Зеркалит fetchConfirmedCounts / countConfirmed
        // (stage_2_vote = confirmed) — раньше getEvent тут был захардкожен в 0.
        val confirmed = dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed)))
            .fetchOne(0, Int::class.java) ?: 0
        return mapOf("going" to going, "maybe" to maybe, "notGoing" to notGoing, "confirmed" to confirmed)
    }

    override fun findEventsToTriggerStage2(now: OffsetDateTime, defaultLeadMinutes: Long): List<Event> =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.eq(EventStatus.upcoming)
                    .and(EVENTS.STAGE_2_TRIGGERED.eq(false))
                    // Пер-событийный интервал Этапа 2 (V67): событие «готово», когда до старта
                    // осталось ≤ его собственного lead (или глобального дефолта при NULL).
                    .and(
                        DSL.condition(
                            "{0} - (COALESCE({1}, {2}) * INTERVAL '1 minute') <= {3}",
                            EVENTS.EVENT_DATETIME,
                            EVENTS.STAGE2_LEAD_MINUTES,
                            DSL.value(defaultLeadMinutes),
                            DSL.value(now)
                        )
                    )
            )
            .fetch()
            .map(mapper::toDomain)

    /**
     * Возвращает ближайшее предстоящее событие среди всех клубов.
     * Используется в ClubsBot.handleWhoIsGoing (команда /кто_идет).
     * Статус должен быть upcoming, stage_1 или stage_2, и event_datetime > now.
     */
    override fun findNextUpcomingEvent(now: OffsetDateTime): Event? =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2)
                    .and(EVENTS.EVENT_DATETIME.gt(now))
            )
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findFutureEventsByClub(clubId: UUID, now: OffsetDateTime): List<Event> =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    .and(EVENTS.EVENT_DATETIME.gt(now))
            )
            .orderBy(EVENTS.EVENT_DATETIME.asc())
            .fetch()
            .map(mapper::toDomain)

    override fun countPastEvents(clubId: UUID, now: OffsetDateTime): Int =
        dsl.selectCount().from(EVENTS)
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(now))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun markCompleted(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.completed)
            .set(EVENTS.UPDATED_AT, OffsetDateTime.now())
            .where(
                EVENTS.ID.eq(id)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
            )
            .execute()
    }

    override fun transitionToStage2(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.stage_2)
            .set(EVENTS.STAGE_2_TRIGGERED, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun cancelActiveEventsByClub(clubId: UUID): Int =
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.cancelled)
            .set(EVENTS.UPDATED_AT, OffsetDateTime.now())
            .where(
                EVENTS.CLUB_ID.eq(clubId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // Важно: у события в stage_2 посещаемость может быть уже отмечена (отметка
                    // разрешена в ~6ч до финального sweep-прохода). Нельзя отменять событие, чья
                    // репутация уже зафиксирована. Парный guard в finalize (finalizeAttendanceBefore
                    // + claimEvent исключают `cancelled`) не даёт ещё-не-финализированному, но уже
                    // отмеченному событию начислить репутацию после отмены.
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
            )
            .execute()

    override fun cancelEvent(eventId: UUID, reason: String?): Int =
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.cancelled)
            .set(EVENTS.CANCELLATION_REASON, reason)
            .set(EVENTS.UPDATED_AT, OffsetDateTime.now())
            .where(
                EVENTS.ID.eq(eventId)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
                    // Только до начала события: event_datetime > now ⇒ посещаемость ещё не может быть
                    // отмечена или финализирована, поэтому отмена никогда не стирает легитимную
                    // посещаемость/репутацию.
                    .and(EVENTS.EVENT_DATETIME.greaterThan(OffsetDateTime.now()))
            )
            .execute()

    override fun markAttendanceMarked(id: UUID): Int =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_MARKED, true)
            // решение (б)=A: окно на оспаривание отсчитывается от этого момента, а не от event_datetime
            // (даже поздняя отметка должна давать участнику полное окно). finalizeAttendanceBefore
            // проверяет условие через COALESCE(attendance_marked_at, event_datetime).
            .set(EVENTS.ATTENDANCE_MARKED_AT, OffsetDateTime.now())
            .where(
                EVENTS.ID.eq(id)
                    // F5-09: закрывает TOCTOU против финализатора. При READ COMMITTED уже
                    // закоммиченный финализатором attendance_finalized=true даёт здесь 0 строк;
                    // markAttendance бросает исключение и откатывает записи setAttendance в той же транзакции.
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
            )
            .execute()

    override fun findEventsNeedingAttendanceReminder(cutoff: OffsetDateTime): List<Event> =
        dsl.selectFrom(EVENTS)
            .where(
                EVENTS.ATTENDANCE_MARKED.isFalse
                    .and(EVENTS.ATTENDANCE_REMINDER_SENT.isFalse)
                    // F5-17: EXP-2 нейтрально финализирует неотмеченные прошедшие события (marked=false,
                    // finalized=true). Без этого условия напоминание всё равно сработает на них → организатор
                    // жмёт «отметить» → markAttendance бросает finalized → гарантированный 400.
                    .and(EVENTS.ATTENDANCE_FINALIZED.isFalse)
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(cutoff))   // событие прошло >= «часов после» назад
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
                    // CC-2: напоминать организатору только когда реально есть ростер для отметки — у
                    // прошедшего события с нулём подтверждённых участников нечего отмечать (да и
                    // setAttendance трогает только строки с final_status=confirmed).
                    .andExists(
                        DSL.selectOne().from(EVENT_RESPONSES).where(
                            EVENT_RESPONSES.EVENT_ID.eq(EVENTS.ID)
                                .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.confirmed))
                        )
                    )
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markAttendanceReminderSent(id: UUID) {
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_REMINDER_SENT, true)
            .where(EVENTS.ID.eq(id))
            .execute()
    }

    override fun findOrganizerTelegramId(eventId: UUID): Long? =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENTS)
            .join(CLUBS).on(CLUBS.ID.eq(EVENTS.CLUB_ID))
            .join(USERS).on(USERS.ID.eq(CLUBS.OWNER_ID))
            .where(EVENTS.ID.eq(eventId))
            .fetchOne(USERS.TELEGRAM_ID)

    override fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID> =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(true)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    // решение (б)=A: окно отсчитывается от момента отметки. COALESCE откатывается к
                    // event_datetime для строк, отмеченных до V24 (attendance_marked_at IS NULL),
                    // поэтому старые отмеченные-но-не-финализированные события финализируются по старой
                    // базе — без backfill.
                    .and(DSL.coalesce(EVENTS.ATTENDANCE_MARKED_AT, EVENTS.EVENT_DATETIME).lessOrEqual(eventDatetimeCutoff))
                    // Отменённое событие никогда не должно финализироваться и начислять репутацию.
                    // Достижимо, поскольку каскад удаления клуба может отменить уже-отмеченное stage_2
                    // событие (отметка разрешена в ~6ч до завершения). Зеркалит соседний метод
                    // neutrallyFinalizeUnmarkedBefore, который уже исключает cancelled.
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
            .returningResult(EVENTS.ID)
            .fetch()
            .mapNotNull { it.value1() }

    override fun neutrallyFinalizeUnmarkedBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID> =
        dsl.update(EVENTS)
            .set(EVENTS.ATTENDANCE_FINALIZED, true)
            .where(
                EVENTS.ATTENDANCE_MARKED.eq(false)
                    .and(EVENTS.ATTENDANCE_FINALIZED.eq(false))
                    .and(EVENTS.EVENT_DATETIME.lessOrEqual(eventDatetimeCutoff))
                    .and(EVENTS.STATUS.ne(EventStatus.cancelled))
            )
            .returningResult(EVENTS.ID)
            .fetch()
            .mapNotNull { it.value1() }

    override fun markPastEventsCompleted(cutoff: OffsetDateTime): Int =
        dsl.update(EVENTS)
            .set(EVENTS.STATUS, EventStatus.completed)
            .where(
                EVENTS.EVENT_DATETIME.lessThan(cutoff)
                    .and(EVENTS.STATUS.`in`(EventStatus.upcoming, EventStatus.stage_1, EventStatus.stage_2))
            )
            .execute()

    private fun countVotes(eventId: UUID, vote: Stage_1Vote): Int =
        dsl.selectCount().from(EVENT_RESPONSES)
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId).and(EVENT_RESPONSES.STAGE_1_VOTE.eq(vote)))
            .fetchOne(0, Int::class.java) ?: 0

    private fun fetchGoingCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_1_VOTE.eq(Stage_1Vote.going))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

    private fun fetchConfirmedCounts(eventIds: List<UUID>): Map<UUID, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        return dsl.select(EVENT_RESPONSES.EVENT_ID, DSL.count())
            .from(EVENT_RESPONSES)
            .where(
                EVENT_RESPONSES.EVENT_ID.`in`(eventIds)
                    .and(EVENT_RESPONSES.STAGE_2_VOTE.eq(Stage_2Vote.confirmed))
            )
            .groupBy(EVENT_RESPONSES.EVENT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }
    }

}
