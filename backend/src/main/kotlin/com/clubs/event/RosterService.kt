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
 * Набор состава формата «🎟 Встреча с местами» (V83, решения PO 2026-08-21 и 2026-08-31).
 *
 * `participant_limit` здесь читается как ПОРОГ («сколько человек нужно»), а не как потолок:
 * голос «Иду» кладёт человека в состав немедленно, отдельного «подтвердите участие» у формата
 * больше нет. Дедлайн набора — момент, когда состав МОЖЕТ закрыться: порог взят — закрывается и
 * встреча состоится; не взят — набор просто продолжается до самой встречи, а организатор
 * получает одно сообщение о недоборе. Ни автоотмены, ни продления: единственное решение при
 * недоборе — отменить встречу, и оно живёт на её странице, как у любой другой встречи.
 *
 * Гонка за места осталась только у ⚡ срочной (Stage2Service).
 *
 * Спека: docs/modules/event-roster-threshold.md.
 */
@Service
class RosterService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val eventPublisher: ApplicationEventPublisher,
    // Глобальный дефолт интервала набора (минут до старта) — тот же ключ, что у Stage2Service.
    @Value("\${events.stage2-trigger-minutes-before:1080}") private val defaultLeadMinutes: Long
) {

    private val log = LoggerFactory.getLogger(RosterService::class.java)

    /**
     * Голос Этапа 1 на встрече с порогом: «Иду» занимает место или встаёт в очередь, любой другой
     * голос из состава выводит. Вызывается из [VoteService.castVote] в той же транзакции, сразу
     * после записи самого голоса.
     *
     * Слот-лок — тот же, что у подтверждения/отказа: два «Иду» на последнее место не должны оба
     * пройти проверку `confirmedCount < limit`.
     */
    @Transactional
    fun applyVote(event: Event, userId: UUID, vote: Stage_1Vote) {
        val limit = event.participantLimit ?: return
        if (!event.isRosterEvent) return

        eventResponseRepository.lockEventSlots(event.id)
        val response = eventResponseRepository.findByEventAndUser(event.id, userId) ?: return

        if (vote == Stage_1Vote.going) {
            // Уже в составе или в очереди — повторный «Иду» ничего не меняет (идемпотентность).
            if (response.stage2Vote == Stage_2Vote.confirmed || response.stage2Vote == Stage_2Vote.waitlisted) return
            val taken = eventResponseRepository.countConfirmed(event.id)
            val place = if (taken < limit) Stage_2Vote.confirmed else Stage_2Vote.waitlisted
            val finalStatus = if (taken < limit) FinalStatus.confirmed else FinalStatus.waitlisted
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
     * Дедлайн набора наступил. Порог взят — состав закрывается, встреча состоится. Не взят —
     * НИЧЕГО не закрывается: набор продолжается до самой встречи, и ближайший тик закроет состав,
     * как только людей станет достаточно (решение PO 2026-08-31, упрощение V83). Организатору при
     * этом уходит ровно одно сообщение — «ждали шестерых, пока четверо».
     *
     * Прежняя развилка «продлить / провести меньшим составом / отменить» с автоотменой по молчанию
     * убрана целиком: три таймера вместо одного никто не мог удержать в голове, а бездействие,
     * отменяющее встречу, противоречило соседнему случаю — распавшемуся составу, где бездействие
     * означает «проводим». Теперь правило одно на оба: **бездействие встречу не отменяет**.
     *
     * Вызывается из [Stage2Service] вместо обычного перехода в Этап 2. Возвращает true, если
     * событие обработано как встреча с порогом.
     */
    @Transactional
    fun handleRosterDeadline(event: Event): Boolean {
        if (!event.isRosterEvent) return false
        val limit = event.participantLimit ?: return false

        val confirmed = eventResponseRepository.countConfirmed(event.id)
        when {
            confirmed >= limit -> closeRoster(event, confirmed)
            // Встреча уже началась, а порог так и не взят: состав замораживаем молча — объявлять
            // «состав собран» посреди встречи не о чем, но зафиксировать его надо. Иначе событие
            // висело бы в наборе до часового прохода завершения, принимая голоса, а список
            // участников для отметки явки остался бы пустым (он читает состав, а не голоса).
            !event.eventDatetime.isAfter(OffsetDateTime.now()) -> {
                eventRepository.transitionToStage2(event.id)
                log.info("Roster frozen at start: eventId={} confirmed={}/{}", event.id, confirmed, limit)
            }
            // Гард `roster_shortfall_at IS NULL` внутри markRosterShortfall и есть «ровно один раз»:
            // тик возвращается к этому событию каждую минуту, пока набор не закроется.
            eventRepository.markRosterShortfall(event.id, OffsetDateTime.now()) > 0 -> {
                eventPublisher.publishEvent(RosterShortfallEvent(event, confirmed, limit))
                log.info("Roster shortfall: eventId={} confirmed={}/{}", event.id, confirmed, limit)
            }
        }
        return true
    }

    /** Момент закрытия набора: дедлайн = старт минус интервал (свой у события или дефолтный). */
    fun rosterDeadline(event: Event): OffsetDateTime =
        event.eventDatetime.minusMinutes((event.stage2LeadMinutes ?: defaultLeadMinutes.toInt()).toLong())

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
