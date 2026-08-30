package com.clubs.event

import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Набор состава формата «🎟 Встреча с местами» (V83, решения PO 2026-08-21).
 *
 * `participant_limit` здесь читается как ПОРОГ («сколько человек нужно»), а не как потолок:
 * голос «Иду» кладёт человека в состав немедленно, отдельного «подтвердите участие» у формата
 * больше нет. К дедлайну набора состав либо собран (встреча состоится), либо нет — и тогда
 * решает организатор. Гонка за места осталась только у ⚡ срочной (Stage2Service).
 *
 * Спека: docs/modules/event-roster-threshold.md.
 */
@Service
class RosterService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val eventService: EventService,
    private val eventPublisher: ApplicationEventPublisher,
    // Глобальный дефолт интервала набора (минут до старта) — тот же ключ, что у Stage2Service.
    @Value("\${events.stage2-trigger-minutes-before:1080}") private val defaultLeadMinutes: Long,
    // Сколько ждём решения организатора при недоборе, прежде чем отменить встречу за него.
    // Дефолт 360 = 6 ч. В минутах, чтобы staging мог ужать для сквозного теста.
    // Env: ROSTER_SHORTFALL_RESPONSE_MINUTES
    @Value("\${events.roster-shortfall-response-minutes:360}") private val shortfallResponseMinutes: Long,
    // Насколько близко к встрече разрешено двигать дедлайн набора продлением. Дефолт 120 = 2 ч:
    // состав, объявленный за час до выхода, никому не помогает. Env: ROSTER_DEADLINE_MIN_LEAD_MINUTES
    @Value("\${events.roster-deadline-min-lead-minutes:120}") private val minLeadMinutes: Long
) {

    private val log = LoggerFactory.getLogger(RosterService::class.java)

    companion object {
        /** Шаги продления набора из DM организатору (часы) — они же подписи кнопок. */
        val EXTEND_OPTIONS_HOURS = listOf(6, 12)
    }

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
     * Дедлайн набора наступил. Набрали — состав закрывается, встреча состоится; не набрали —
     * фиксируем недобор и зовём организатора решать. Вызывается из [Stage2Service] вместо обычного
     * перехода в Этап 2. Возвращает true, если событие обработано как встреча с порогом.
     */
    @Transactional
    fun handleRosterDeadline(event: Event): Boolean {
        if (!event.isRosterEvent) return false
        val limit = event.participantLimit ?: return false

        val confirmed = eventResponseRepository.countConfirmed(event.id)
        if (confirmed >= limit) {
            closeRoster(event, confirmed)
        } else {
            eventRepository.markRosterShortfall(event.id, OffsetDateTime.now())
            eventPublisher.publishEvent(RosterShortfallEvent(event, confirmed, limit))
            log.info("Roster shortfall: eventId={} confirmed={}/{}", event.id, confirmed, limit)
        }
        return true
    }

    /**
     * Проход по встречам, которые закрылись недобором и ждут решения организатора: состав добрался
     * (кто-то проголосовал, пока организатор думал) — закрываем; окно ответа вышло — отменяем за
     * него. Своего шедулера у прохода нет: его дёргает тот же тик, что закрывает наборы
     * ([Stage2Service.triggerStage2ForReadyEvents]) — два таймера по одной и той же таблице
     * событий были бы лишней сущностью.
     *
     * REQUIRES_NEW: сбой на одной зависшей встрече не должен откатывать переходы, которые тик
     * уже сделал в своей транзакции.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processShortfallEvents() {
        val now = OffsetDateTime.now()
        eventRepository.findEventsInRosterShortfall().forEach { event ->
            try {
                val limit = event.participantLimit ?: return@forEach
                val confirmed = eventResponseRepository.countConfirmed(event.id)
                when {
                    confirmed >= limit -> closeRoster(event, confirmed)
                    !now.isBefore(autoCancelAt(event)) -> {
                        eventService.cancelBySystem(event, "Не набрался состав")
                        log.info("Roster auto-cancelled: eventId={} confirmed={}/{}", event.id, confirmed, limit)
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to process roster shortfall for event ${event.id}", e)
            }
        }
    }

    /**
     * Организатор продлевает набор на [hours] часов от текущего момента. Дедлайн хранится
     * интервалом до старта (`stage2_lead_minutes`), поэтому продление — это его пересчёт, а не
     * новая колонка: иначе шаблоны встреч (V79), которые носят именно интервал, не переносились бы
     * на новую дату. Ближе [minLeadMinutes] до встречи двигать нельзя.
     */
    @Transactional
    fun extendRoster(event: Event, hours: Int) {
        requireShortfall(event)
        val latestDeadline = event.eventDatetime.minusMinutes(minLeadMinutes)
        val newDeadline = OffsetDateTime.now().plusHours(hours.toLong())
        if (newDeadline.isAfter(latestDeadline)) {
            throw ValidationException("До встречи слишком мало времени — набор больше не продлить")
        }
        val newLeadMinutes = Duration.between(newDeadline, event.eventDatetime).toMinutes().toInt()
        if (eventRepository.extendRosterDeadline(event.id, newLeadMinutes) == 0) {
            throw ValidationException("Набор уже закрыт")
        }
        // Закреп в чате несёт дедлайн набора — после сдвига он обязан перерисоваться.
        eventPublisher.publishEvent(EventRosterChangedEvent(event.id))
        log.info("Roster extended: eventId={} hours={} newLeadMinutes={}", event.id, hours, newLeadMinutes)
    }

    /** Организатор решил провести встречу неполным составом — закрываем набор как есть. */
    @Transactional
    fun proceedWithPartialRoster(event: Event) {
        requireShortfall(event)
        closeRoster(event, eventResponseRepository.countConfirmed(event.id))
    }

    /** Когда встреча отменится сама, если организатор так и не ответит на DM о недоборе. */
    fun autoCancelAt(event: Event): OffsetDateTime {
        // Фолбэк на now() — для случая, когда отметку недобора только что поставили, а объект
        // события прочитан до неё (RosterListener считает дедлайн сразу после фиксации).
        val byResponseWindow = (event.rosterShortfallAt ?: OffsetDateTime.now())
            .plusMinutes(shortfallResponseMinutes)
        // Ждать дольше, чем «за 2 часа до встречи», нельзя: участникам нужно узнать об отмене,
        // пока они ещё не вышли из дома.
        val byEventProximity = event.eventDatetime.minusMinutes(minLeadMinutes)
        return if (byResponseWindow.isBefore(byEventProximity)) byResponseWindow else byEventProximity
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

    /** Действия из DM организатору применимы, только пока набор реально висит в недоборе. */
    private fun requireShortfall(event: Event) {
        if (event.status != EventStatus.upcoming || event.rosterShortfallAt == null) {
            throw ValidationException("Набор уже закрыт")
        }
    }
}
