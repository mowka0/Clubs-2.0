package com.clubs.event

import com.clubs.common.dto.PageResponse
import com.clubs.generated.jooq.enums.EventStatus
import java.time.OffsetDateTime
import java.util.UUID

interface EventRepository {

    fun create(request: CreateEventRequest, clubId: UUID, createdBy: UUID): Event

    fun findById(id: UUID): Event?

    fun findByClubId(clubId: UUID, status: EventStatus?, page: Int, size: Int): PageResponse<EventListItemDto>

    /**
     * Возвращает ВСЕ события указанного клуба (любой статус, любая дата) в паре с
     * кэшированным счётчиком голосов "иду". Используется единой лентой активностей, где
     * пагинация/сортировка решаются на более высоком уровне (in-memory слияние со складчинами).
     */
    fun findAllByClubWithGoingCount(clubId: UUID): List<EventWithGoingCount>

    /**
     * ID событий клуба, которые прямо сейчас требуют действия от [userId]:
     * голосование этапа 1 (голосование открыто, ещё не голосовал) или подтверждение
     * этапа 2 (проголосовал идёт/может быть, ещё не подтвердил). Используется лентой активностей
     * для закрепления требующих действия событий наверху. Тот же предикат, что и в
     * сортировке action-required у [findMyFeed].
     */
    fun findActionRequiredEventIds(clubId: UUID, userId: UUID, now: OffsetDateTime): Set<UUID>

    fun findMyFeed(userId: UUID, page: Int, size: Int): PageResponse<MyFeedItem>

    fun getVoteCounts(eventId: UUID): Map<String, Int>

    /** События, готовые к Этапу 2: до старта ≤ их собственного lead (или [defaultLeadMinutes] при NULL). */
    fun findEventsToTriggerStage2(now: OffsetDateTime, defaultLeadMinutes: Long): List<Event>

    fun findNextUpcomingEvent(now: OffsetDateTime): Event?

    /**
     * Будущие неотменённые события клуба (status IN upcoming/stage_1/stage_2, event_datetime > now),
     * ближайшее первым. «Живой закреп»: backfill пинов при включении тумблера и строка
     * «Следующая — …» в посте-итоге.
     */
    fun findFutureEventsByClub(clubId: UUID, now: OffsetDateTime): List<Event>

    /**
     * Все прошедшие неотменённые события клуба (event_datetime <= now, status != cancelled) —
     * порядковый «Встреча №N» в посте-итоге. Та же семантика, что totalMeetings в ClubFacts.
     */
    fun countPastEvents(clubId: UUID, now: OffsetDateTime): Int

    /**
     * Переводит одно событие в completed (если оно ещё в активном статусе, cancelled не трогается).
     * Вызывается при отметке явки (PO 2026-07-08): организатор зафиксировал, что встреча прошла, —
     * событие уходит из «предстоящих» сразу, не дожидаясь часового EventCompletionService.
     */
    fun markCompleted(id: UUID)

    fun transitionToStage2(id: UUID)

    /**
     * Каскад мягкого удаления клуба: отменяет каждое незавершённое событие клуба [clubId]
     * (status IN upcoming/stage_1/stage_2, посещаемость не финализирована), чтобы шедулеры
     * перестали их обрабатывать и они выпали из лент. Завершённые/отменённые и уже финализированные
     * события не трогаются — их репутация зафиксирована. Возвращает число обновлённых строк.
     */
    fun cancelActiveEventsByClub(clubId: UUID): Int

    /**
     * Отменяет одно ещё не начавшееся событие (F5-14): status → cancelled + опциональная причина, но ТОЛЬКО
     * пока оно ещё активно и `event_datetime > now`, так что начавшийся/финализированный митап
     * никогда не отменяется задним числом (это стёрло бы легитимную посещаемость). Возвращает число
     * затронутых строк (0 ⇒ не отменяемо → вызывающий возвращает 409).
     */
    fun cancelEvent(eventId: UUID, reason: String?): Int

    /**
     * Помечает событие как attendance-marked и проставляет attendance_marked_at = now() (решение (б):
     * окно для оспаривания отсчитывается с момента отметки). Защищено условием attendance_finalized=false
     * (F5-09): возвращает 0, если финализирующий уже финализировал событие, чтобы вызывающий мог отклонить отметку.
     */
    fun markAttendanceMarked(id: UUID): Int

    // --- Шедулер напоминаний (EventReminderScheduler) ---
    // Напоминание «подтверди участие» за ~2ч (фича A) удалено PO 2026-07-08 — лишний пинг.

    /**
     * Фича B: прошедшие, неотменённые события, посещаемость которых ещё не отмечена и для которых
     * напоминание организатору ещё не отправлено. [cutoff] = now - окно "часов после" (по умолчанию 24ч).
     */
    fun findEventsNeedingAttendanceReminder(cutoff: OffsetDateTime): List<Event>

    fun markAttendanceReminderSent(id: UUID)

    /** Telegram id организатора (владельца) клуба события, или null если не задан. */
    fun findOrganizerTelegramId(eventId: UUID): Long?

    /** Финализирует посещаемость для прошедших, отмеченных, ещё не финализированных событий. Возвращает id финализированных событий. */
    fun finalizeAttendanceBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID>

    /**
     * EXP-2: нейтрально финализирует прошедшие, **неотмеченные**, ещё не финализированные, неотменённые
     * события, чья дата наступила в момент [eventDatetimeCutoff] или раньше. Выставляет
     * `attendance_finalized = true`, оставляя `attendance_marked = false`, так что пайплайн репутации
     * (который забирает только marked+finalized события) никогда не создаёт для них строки в леджере —
     * событие просто не засчитывается. Возвращает id финализированных событий (для логирования).
     * Для них НЕ публикуется AttendanceFinalizedEvent.
     */
    fun neutrallyFinalizeUnmarkedBefore(eventDatetimeCutoff: OffsetDateTime): List<UUID>

    /**
     * Переводит активные события (upcoming / stage_1 / stage_2), чья дата раньше [cutoff],
     * в [EventStatus.completed]. Не трогает уже завершённые или отменённые события.
     * Возвращает число обновлённых строк.
     */
    fun markPastEventsCompleted(cutoff: OffsetDateTime): Int
}

data class EventWithGoingCount(
    val event: Event,
    val goingCount: Int,
    // Размер подтверждённого состава этапа 2. Позволяет ленте переключиться со счётчика
    // "идёт" этапа 1 на финальный счётчик "подтв." после закрытия голосования (F5-21).
    // По умолчанию 0, чтобы тесты, не зависящие от этого счётчика, строили проекцию только с goingCount.
    val confirmedCount: Int = 0
)
