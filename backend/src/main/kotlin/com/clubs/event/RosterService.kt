package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
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

/** Итог «Проводим»: состав на момент отметки и стояла ли она уже (повторный вызов — no-op). */
data class ProceedResult(val confirmedCount: Int, val alreadyDecided: Boolean)

/**
 * Набор состава обычной встречи (v2, решение PO 2026-09-02).
 *
 * `participant_limit` — всегда ПОТОЛОК: голос «Иду» занимает место или встаёт в очередь.
 * `min_participants` — ПОРОГ по желанию организатора, и он условие СБОРА состава, а не проведения:
 *  - ① дедлайн набора: минимума нет или он взят → состав закрыт; не взят → встреча отменяется;
 *  - ② за `roster-warning-minutes-before-deadline` до дедлайна при недоборе — DM организатору;
 *  - ③ после закрытия состав просел ниже минимума — DM организатору, встречу НЕ отменяет.
 *
 * Отмена по недобору — не «робот решил», а исполнение правила, названного организатору при
 * создании и привязанного к дедлайну, который он сам и выбрал. Автоотмены по молчанию после
 * закрытия нет (три причины — § 1.2 спеки): бездействие встречу не отменяет.
 *
 * Спека: docs/modules/event-formats.md.
 */
@Service
class RosterService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val eventService: EventService,
    private val eventPublisher: ApplicationEventPublisher,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    // Глобальный дефолт интервала набора (минут до старта) — тот же ключ, что у Stage2Service.
    @Value("\${events.stage2-trigger-minutes-before:1080}") private val defaultLeadMinutes: Long,
    // Правило ②: за сколько минут до дедлайна предупреждать о недоборе. Env: ROSTER_WARNING_MINUTES_BEFORE_DEADLINE
    @Value("\${events.roster-warning-minutes-before-deadline:180}") private val warningMinutes: Long
) {

    private val log = LoggerFactory.getLogger(RosterService::class.java)

    companion object {
        /** Причина автоотмены, когда из закрытого состава ушёл последний участник. */
        const val ROSTER_DISBANDED_REASON = "Состав распался: не осталось участников"
    }

    /**
     * Голос Этапа 1 на встрече с местами: «Иду» занимает место, любой другой голос из состава
     * выводит. Вызывается из [VoteService.castVote] в той же транзакции, сразу после записи голоса.
     *
     * Слот-лок — тот же, что у подтверждения/отказа: два «Иду» на последнее место не должны оба
     * пройти проверку `confirmedCount < limit`.
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
            val fits = taken < limit
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
     * Правило ①: дедлайн набора наступил. Минимума нет или он взят — состав закрывается при любом
     * числе участников; минимум не взят — встреча отменяется, и организатор знал об этом, когда
     * включал минимум.
     *
     * Вызывается из [Stage2Service] вместо обычного перехода в Этап 2. Возвращает true, если
     * событие обработано как встреча с набором.
     */
    @Transactional
    fun handleRosterDeadline(event: Event): Boolean {
        if (!event.isRosterEvent) return false
        val limit = event.participantLimit ?: return false
        val min = event.minParticipants

        val confirmed = eventResponseRepository.countConfirmed(event.id)
        when {
            // Встреча успела начаться, а тик дошёл только сейчас: объявлять что-либо посреди
            // встречи не о чем, но состав зафиксировать надо — иначе событие висело бы в наборе,
            // принимая голоса, а список для отметки явки остался бы пустым (он читает состав, а
            // не голоса Этапа 1). Отменять начавшуюся встречу тоже поздно, и SQL-guard внутри
            // cancelEvent этого не даст.
            !event.eventDatetime.isAfter(OffsetDateTime.now()) -> {
                eventRepository.transitionToStage2(event.id)
                log.info("Roster frozen at start: eventId={} confirmed={}/{}", event.id, confirmed, limit)
            }
            min == null || confirmed >= min -> closeRoster(event, confirmed)
            // Минимум не взят: система выполняет обещание, названное правилом формата.
            else -> {
                eventService.cancelBySystem(event, shortfallReason(min))
                log.info("Roster shortfall cancel: eventId={} confirmed={}/{}", event.id, confirmed, min)
            }
        }
        return true
    }

    /**
     * Правило ②: проход тика по встречам, у которых момент предупреждения наступил (§ 3.2 спеки).
     * Зовётся ПОСЛЕ дедлайнов в том же проходе — предупреждение не должно догонять отмену.
     */
    @Transactional
    fun sendDueRosterWarnings(now: OffsetDateTime) {
        eventRepository.findEventsForRosterWarning(now, defaultLeadMinutes, warningMinutes).forEach { event ->
            try {
                handleRosterWarning(event, now)
            } catch (e: Exception) {
                log.error("Failed to process roster warning for event ${event.id}", e)
            }
        }
    }

    /**
     * Отметка ставится в ЛЮБОМ случае — второго предупреждения не будет, даже если состав потом
     * просядет: иначе ② стало бы вторым таймером. DM уходит только при недоборе.
     */
    @Transactional
    fun handleRosterWarning(event: Event, now: OffsetDateTime = OffsetDateTime.now()) {
        val min = event.minParticipants ?: return
        if (eventRepository.markRosterWarningSent(event.id, now) == 0) return
        val confirmed = eventResponseRepository.countConfirmed(event.id)
        if (confirmed >= min) {
            log.info("Roster warning not needed: eventId={} confirmed={}/{}", event.id, confirmed, min)
            return
        }
        eventPublisher.publishEvent(RosterWarningEvent(event, confirmed, min, rosterDeadline(event)))
        log.info("Roster warning: eventId={} confirmed={}/{}", event.id, confirmed, min)
    }

    /**
     * «Проводим» (§ 4 спеки): организатор подтверждает встречу составом ниже минимума. Это
     * отметка «решено», а НЕ снятие минимума — иначе отказ, который был бесплатным, стал бы
     * стоить 100: правила поменялись бы под ногами у людей, которые ничего не делали.
     *
     * Один сервисный метод для REST и callback-кнопки бота: права проверяются здесь, потому что
     * `callback_data` подделываема — угадав id встречи, чужой не должен ничего сделать.
     */
    @Transactional
    fun proceed(eventId: UUID, userId: UUID): ProceedResult {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        clubRoleGuard.requireCapability(club, userId, ClubCapability.MANAGE_EVENTS)

        val min = event.minParticipants ?: throw ValidationException("У встречи нет минимума — подтверждать нечего")
        when {
            event.status == EventStatus.cancelled -> throw ValidationException("Встреча отменена")
            !event.eventDatetime.isAfter(OffsetDateTime.now()) -> throw ValidationException("Встреча уже началась")
            event.status != EventStatus.stage_2 -> throw ValidationException("Набор ещё идёт — состав не закрыт")
        }
        val confirmed = eventResponseRepository.countConfirmed(eventId)
        if (event.isRosterDecided) return ProceedResult(confirmed, alreadyDecided = true)
        // Кнопка из старого DM после восстановления состава не должна ставить бессрочную отметку.
        if (confirmed >= min) throw ValidationException("Состав не ниже минимума — подтверждать нечего")

        val marked = eventRepository.markRosterDecided(eventId) > 0
        if (marked) {
            // Закреп получает строку «Организатор подтвердил …» — перерисовка на AFTER_COMMIT.
            eventPublisher.publishEvent(EventRosterChangedEvent(eventId))
            log.info("Roster decided: eventId={} userId={} confirmed={}/{}", eventId, userId, confirmed, min)
        }
        return ProceedResult(confirmed, alreadyDecided = !marked)
    }

    /** Момент закрытия набора: дедлайн = старт минус интервал (свой у события или дефолтный). */
    fun rosterDeadline(event: Event): OffsetDateTime =
        RosterSchedule.deadline(event.eventDatetime, event.stage2LeadMinutes, defaultLeadMinutes)

    /** Причина отмены, которую увидят участники в DM и на странице встречи. */
    private fun shortfallReason(min: Int) = "Не набрали $min участников к закрытию набора"

    /** Состав закрыт: голоса больше не набирают его, встреча состоится. */
    private fun closeRoster(event: Event, confirmed: Int) {
        eventRepository.transitionToStage2(event.id)
        // Напоминание — одно на человека НА ЭТАП (V86): после закрытия у молчуна снова есть о
        // чём напоминать, и отметки набора сбрасываются.
        eventResponseRepository.clearStage2Reminders(event.id)
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

    /**
     * Место освободилось не отказом, а киком или выходом из клуба: строку ответа вызывающий уже
     * удалил и держит `lockEventSlots`. Дальше — тот же путь, что у отказа
     * ([Stage2Service.declineParticipation]): повысить первого из очереди, а в закрытом составе
     * заметить, что состав опустел (встреча отменяется) или пробит минимум (DM организатору).
     * До этого кик и выход двигали очередь напрямую и обе проверки обходили: последний
     * участник мог уйти из клуба, и встреча висела с нулём в составе.
     *
     * На наборе (`upcoming`) — только повышение: состав ещё не объявлен, отменять нечего.
     * Возвращает id повышенного из очереди, если он был.
     */
    @Transactional
    fun releaseSeat(eventId: UUID): UUID? {
        val event = eventRepository.findById(eventId) ?: return null
        val promotedUserId = eventResponseRepository.promoteFirstWaitlisted(eventId)
        // Живой закреп: место освободилось (и, возможно, перезанято из очереди).
        eventPublisher.publishEvent(EventRosterChangedEvent(eventId))
        if (promotedUserId != null) eventPublisher.publishEvent(WaitlistPromotedEvent(eventId, promotedUserId))

        if (event.status != EventStatus.stage_2 || event.isOpenEvent) return promotedUserId
        val count = eventResponseRepository.countConfirmed(eventId)
        settleClosedRoster(event, count)
        log.info("Seat released: eventId={} promoted={} confirmed={}/{}", eventId, promotedUserId, count, event.participantLimit)
        return promotedUserId
    }

    /**
     * Что делать с закрытым составом после того, как из него кто-то ушёл (отказ, кик, выход):
     *  - опустел до нуля → отмена сразу, тем же каскадом, что вручную. Это поступок, а не тишина:
     *    человек, взявший обещание, его снял. Отличается от закрытия набора с нулём, где отмены
     *    нет — там состав никогда и не собирался;
     *  - пересёк минимум вниз (строгое равенство `min − 1`, чтобы сигналить ровно на черте) и
     *    «Проводим» не нажато → правило ③, DM организатору. Повторяется на каждом новом
     *    пересечении: вернулись к минимуму и снова упали — снова DM.
     * Без минимума неполный состав ничего не значит: место просто пустует.
     */
    fun settleClosedRoster(event: Event, confirmedCount: Int) {
        val min = event.minParticipants
        when {
            confirmedCount == 0 -> eventService.cancelBySystem(event, ROSTER_DISBANDED_REASON)
            min != null && confirmedCount == min - 1 && !event.isRosterDecided ->
                eventPublisher.publishEvent(RosterBrokenEvent(event, confirmedCount, min))
        }
    }
}
