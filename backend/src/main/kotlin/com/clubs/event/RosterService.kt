package com.clubs.event

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Набор состава для форматов с лимитом (V85, решение PO 2026-08-31).
 *
 * `participant_limit` читается через `limit_kind`, и от него зависит всё поведение набора:
 *  - **MIN** — лимит это ПОРОГ. Голос «Иду» всегда кладёт в состав, верхней границы нет;
 *    в дедлайн порог либо взят (встреча состоится), либо встреча отменяется.
 *  - **MAX** — лимит это ПОТОЛОК. Голос «Иду» занимает место или встаёт в очередь; в дедлайн
 *    состав закрывается при любом числе участников, недобора у формата не существует.
 *
 * Отмена по недобору — не «робот решил», а исполнение правила, названного организатору при
 * создании и привязанного к дедлайну, который он сам и выбрал. Продления набора, окна ответа
 * организатора и автоотмены по молчанию не существует: бездействие встречу не отменяет.
 *
 * Спека: docs/modules/event-formats.md.
 */
@Service
class RosterService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val eventService: EventService,
    private val eventPublisher: ApplicationEventPublisher,
    // Глобальный дефолт интервала набора (минут до старта) — тот же ключ, что у Stage2Service.
    @Value("\${events.stage2-trigger-minutes-before:1080}") private val defaultLeadMinutes: Long
) {

    private val log = LoggerFactory.getLogger(RosterService::class.java)

    /**
     * Голос Этапа 1 на встрече с лимитом: «Иду» занимает место, любой другой голос из состава
     * выводит. Вызывается из [VoteService.castVote] в той же транзакции, сразу после записи голоса.
     *
     * Слот-лок — тот же, что у подтверждения/отказа: два «Иду» на последнее место MAX-встречи не
     * должны оба пройти проверку `confirmedCount < limit`.
     */
    @Transactional
    fun applyVote(event: Event, userId: UUID, vote: Stage_1Vote) {
        val limit = event.participantLimit ?: return

        eventResponseRepository.lockEventSlots(event.id)
        val response = eventResponseRepository.findByEventAndUser(event.id, userId) ?: return

        if (vote == Stage_1Vote.going) {
            // Уже в составе или в очереди — повторный «Иду» ничего не меняет (идемпотентность).
            if (response.stage2Vote == Stage_2Vote.confirmed || response.stage2Vote == Stage_2Vote.waitlisted) return
            val taken = eventResponseRepository.countConfirmed(event.id)
            // У MIN верхней границы нет: лимит — порог, «нужно минимум столько», и состав сверх
            // него растёт свободно. Очередь у формата недостижима по построению.
            val fits = event.format == EventFormat.MIN || taken < limit
            val place = if (fits) Stage_2Vote.confirmed else Stage_2Vote.waitlisted
            val finalStatus = if (fits) FinalStatus.confirmed else FinalStatus.waitlisted
            eventResponseRepository.updateStage2Vote(response.id, place, finalStatus)
            log.info("Roster vote: eventId={} userId={} place={} taken={}/{}", event.id, userId, place, taken, limit)
            return
        }

        // «Возможно» / «Не иду» — выход из набора. Это НЕ отказ (declined): состав ещё не объявлен,
        // никто на человека не рассчитывал, и дорога назад должна остаться открытой.
        if (response.stage2Vote == null) return
        val heldSlot = response.stage2Vote == Stage_2Vote.confirmed
        eventResponseRepository.clearStage2Vote(response.id)
        if (heldSlot) promoteFromWaitlist(event.id)
        log.info("Roster leave: eventId={} userId={} vote={} heldSlot={}", event.id, userId, vote, heldSlot)
    }

    /**
     * Дедлайн набора наступил. Что случится — свойство формата, и организатор знал об этом,
     * когда его выбирал: MAX закрывает состав при любом числе участников, MIN закрывает только
     * при взятом пороге, а иначе отменяет встречу.
     *
     * Вызывается из [Stage2Service] вместо обычного перехода в Этап 2. Возвращает true, если
     * событие обработано как встреча с набором.
     */
    @Transactional
    fun handleRosterDeadline(event: Event): Boolean {
        if (!event.isRosterEvent) return false
        val limit = event.participantLimit ?: return false

        val confirmed = eventResponseRepository.countConfirmed(event.id)
        when {
            // Встреча успела начаться, а тик дошёл только сейчас: объявлять что-либо посреди
            // встречи не о чем, но состав зафиксировать надо — иначе событие висело бы в наборе,
            // принимая голоса, а список для отметки явки остался бы пустым (он читает состав, а
            // не голоса Этапа 1). Отменять начавшуюся встречу тоже поздно, и SQL-guard внутри
            // cancelEvent этого не даст — поэтому ветка общая для обоих форматов.
            !event.eventDatetime.isAfter(OffsetDateTime.now()) -> {
                eventRepository.transitionToStage2(event.id)
                log.info("Roster frozen at start: eventId={} confirmed={}/{}", event.id, confirmed, limit)
            }
            event.format == EventFormat.MAX || confirmed >= limit -> closeRoster(event, confirmed)
            // MIN, порог не взят: система выполняет обещание, названное правилом формата.
            else -> {
                eventService.cancelBySystem(event, shortfallReason(limit))
                log.info("Roster shortfall cancel: eventId={} confirmed={}/{}", event.id, confirmed, limit)
            }
        }
        return true
    }

    /** Момент закрытия набора: дедлайн = старт минус интервал (свой у события или дефолтный). */
    fun rosterDeadline(event: Event): OffsetDateTime =
        event.eventDatetime.minusMinutes((event.stage2LeadMinutes ?: defaultLeadMinutes.toInt()).toLong())

    /** Причина отмены, которую увидят участники в DM и на странице встречи. */
    private fun shortfallReason(limit: Int) = "Не набрали $limit участников к закрытию набора"

    /** Состав закрыт: голоса больше не набирают порог, встреча состоится. */
    private fun closeRoster(event: Event, confirmed: Int) {
        eventRepository.transitionToStage2(event.id)
        // DM «состав собран» и перерисовка закрепа — на AFTER_COMMIT, как Stage2StartedEvent:
        // @Async-рассылка обязана читать уже закоммиченный состав.
        eventPublisher.publishEvent(RosterClosedEvent(event, confirmed))
        eventPublisher.publishEvent(EventRosterChangedEvent(event.id))
        log.info("Roster closed: eventId={} confirmed={}/{}", event.id, confirmed, event.participantLimit)
    }

    /** Освободившееся место сразу отдаётся первому в очереди (и он получает DM). */
    private fun promoteFromWaitlist(eventId: UUID) {
        val first = eventResponseRepository.findFirstWaitlisted(eventId) ?: return
        eventResponseRepository.updateStage2Vote(first.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
        eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, first.userId))
    }
}
