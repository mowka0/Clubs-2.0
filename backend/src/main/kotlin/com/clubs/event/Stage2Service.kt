package com.clubs.event

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import com.clubs.generated.jooq.enums.Stage_2Vote
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class Stage2Service(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val membershipRepository: MembershipRepository,
    private val eventPublisher: ApplicationEventPublisher,
    // Упреждение (минут до старта события), при котором предстоящее событие переходит в Stage 2.
    // По умолчанию 24 ч. Единица «минуты» позволяет staging'у укоротить его для сквозного теста
    // двухэтапки: малое значение оставляет короткое окно голосования Этапа 1 до перехода
    // события в подтверждение.
    @Value("\${events.stage2-trigger-minutes-before:1440}") private val stage2TriggerMinutesBefore: Long
) {
    private val log = LoggerFactory.getLogger(Stage2Service::class.java)

    // Окно подтверждения — [flip .. старт события], а сам flip случается где угодно внутри одного
    // периода опроса после границы триггера — поэтому тик должен быть сильно мельче упреждения
    // триггера. Старый захардкоженный тик 5 мин съедал короткое staging-упреждение (3 мин) целиком:
    // flip часто приходился уже после старта события, оставляя окно нулевой длины.
    @Scheduled(fixedDelayString = "\${events.stage2-poll-ms:60000}")
    @Transactional
    fun triggerStage2ForReadyEvents() {
        val cutoff = OffsetDateTime.now().plusMinutes(stage2TriggerMinutesBefore)
        val events = eventRepository.findEventsToTriggerStage2(cutoff)
        events.forEach { event ->
            try {
                triggerStage2(event)
                log.info("Stage 2 triggered for event ${event.id}")
            } catch (e: Exception) {
                log.error("Failed to trigger Stage 2 for event ${event.id}", e)
            }
        }
    }

    private fun triggerStage2(event: Event) {
        eventRepository.transitionToStage2(event.id)

        // Первые N проголосовавших going (по stage_1_timestamp) сохраняют stage_2_vote = null —
        // они подтверждают явно. Остальные сразу становятся waitlisted.
        val goingVoters = eventResponseRepository.findGoingByEventOrderByTimestamp(event.id)
        goingVoters.forEachIndexed { index, response ->
            if (index >= event.participantLimit) {
                eventResponseRepository.updateStage2Vote(response.id, Stage_2Vote.waitlisted, FinalStatus.waitlisted)
            }
        }

        // S2T-2: просим проголосовавших going/maybe подтвердить участие. Без этого DM никто не
        // узнает, что начался Stage 2, никто не подтвердит, и все автоматически истекут к старту
        // события. Переход через AFTER_COMMIT (Stage2StartedListener) — @Async DM должен читать
        // уже закоммиченные строки.
        // Поздний flip (событие уже началось) всё равно происходит — от него зависят цикл
        // истечения и жизненный цикл завершения — но окно подтверждения уже закрыто
        // (confirmParticipation отклоняет после старта события), так что DM был бы бесполезен.
        if (event.eventDatetime.isAfter(OffsetDateTime.now())) {
            eventPublisher.publishEvent(Stage2StartedEvent(event))
        } else {
            log.info("Stage 2 confirm DM skipped for event ${event.id} — flipped after event start (window closed)")
        }
    }

    @Transactional
    fun confirmParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        // Bug B: окно подтверждения закрывается в момент старта события. Иначе прошедшее
        // событие остаётся в stage_2 до часового прохода EventCompletionService, оставляя
        // окно, в котором можно подтвердить уже случившееся событие. См. events.md.
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Confirmation window has closed")
        }

        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        // S2-01/F5-07: решение по слоту ниже — неатомарный read-modify-write
        // (countConfirmed < limit → updateStage2Vote). Два одновременных подтверждения на
        // последний слот оба пройдут проверку и переполнят ростер. Advisory lock на событие
        // сериализует любую мутацию слотов (общий с declineParticipation, который закрывает
        // F5-11 — двойное повышение одного и того же waitlisted-пользователя). Берётся до ЛЮБОГО
        // чтения event_responses, чтобы заблокированная транзакция перечитала закоммиченное
        // состояние, как только получит лок.
        eventResponseRepository.lockEventSlots(eventId)

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage1Vote != Stage_1Vote.going && response.stage1Vote != Stage_1Vote.maybe) {
            throw ValidationException("You voted not_going for this event")
        }

        if (response.stage2Vote == Stage_2Vote.confirmed) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "confirmed", count, event.participantLimit)
        }

        if (response.stage2Vote == Stage_2Vote.declined) {
            throw ValidationException("You already declined participation")
        }

        if (response.stage2Vote == Stage_2Vote.waitlisted) {
            // FIFO (S2-02/S2T-3): повышение из waitlist управляется системой — происходит
            // только когда подтверждённый участник отказывается (declineParticipation →
            // findFirstWaitlisted), строго по stage_1_timestamp. Waitlisted-пользователь,
            // повторно подтверждающий участие, НЕ должен обгонять в гонке ранее вставшего в
            // очередь участника за освободившийся слот, поэтому оставляем его в waitlisted
            // (идемпотентно).
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "waitlisted", count, event.participantLimit)
        }

        val confirmedCount = eventResponseRepository.countConfirmed(eventId)

        val newStatus: Stage_2Vote
        val finalStatus: FinalStatus
        if (confirmedCount < event.participantLimit) {
            newStatus = Stage_2Vote.confirmed
            finalStatus = FinalStatus.confirmed
        } else {
            newStatus = Stage_2Vote.waitlisted
            finalStatus = FinalStatus.waitlisted
        }

        eventResponseRepository.updateStage2Vote(response.id, newStatus, finalStatus)

        val newCount = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, newStatus.literal, newCount, event.participantLimit)
    }

    @Transactional
    fun declineParticipation(eventId: UUID, userId: UUID): ConfirmResponseDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")

        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Event is not in confirmation stage")
        }

        // Bug B: зеркалит confirmParticipation — нельзя отказаться после старта события.
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Confirmation window has closed")
        }

        // S2-05 (OWASP A01): отказ — это изменение состояния, которое освобождает слот и
        // повышает первого в очереди waitlisted-участника — должен требовать активное
        // членство, симметрично с подтверждением.
        if (!membershipRepository.isMember(userId, event.clubId)) {
            throw ForbiddenException("Not a member of this club")
        }

        // F5-11: тот же advisory lock, что и в confirmParticipation — два одновременных отказа
        // оба прочитают одну и ту же строку findFirstWaitlisted и повысят одного пользователя
        // за два освободившихся слота, безвозвратно потеряв слот.
        eventResponseRepository.lockEventSlots(eventId)

        val response = eventResponseRepository.findByEventAndUser(eventId, userId)
            ?: throw ValidationException("You didn't vote in this event")

        if (response.stage2Vote == Stage_2Vote.declined) {
            val count = eventResponseRepository.countConfirmed(eventId)
            return ConfirmResponseDto(eventId, "declined", count, event.participantLimit)
        }

        val wasConfirmed = response.stage2Vote == Stage_2Vote.confirmed
        eventResponseRepository.updateStage2Vote(response.id, Stage_2Vote.declined, FinalStatus.declined)

        if (wasConfirmed) {
            val firstWaitlisted = eventResponseRepository.findFirstWaitlisted(eventId)
            firstWaitlisted?.let {
                eventResponseRepository.updateStage2Vote(it.id, Stage_2Vote.confirmed, FinalStatus.confirmed)
            }
        }

        val count = eventResponseRepository.countConfirmed(eventId)
        return ConfirmResponseDto(eventId, "declined", count, event.participantLimit)
    }

    /**
     * Feature A (PRD §4.4.2 / §623 "авто-отклонение"): как только событие началось, любой
     * проголосовавший going/maybe, кто так и не подтвердил (stage_2_vote IS NULL), переводится
     * в явное терминальное состояние [Stage_2Vote.expired_no_confirm], чтобы ростер был честным,
     * а не содержал неоднозначные NULL-дыры. Единое идемпотентное массовое обновление — предикат
     * `stage_2_vote IS NULL` делает повторные запуски no-op'ами и никогда не трогает строки
     * confirmed/waitlisted/declined. На репутацию это не влияет (она читает только
     * final_status = confirmed). См. events.md § "Закрытие окна подтверждения".
     */
    @Scheduled(fixedDelayString = "\${events.stage2-expire-poll-ms:300000}")
    @Transactional
    fun expireUnconfirmedParticipants() {
        val count = eventResponseRepository.expireUnconfirmedForStartedEvents(OffsetDateTime.now())
        if (count > 0) log.info("Auto-expired {} unconfirmed Stage 2 responses", count)
    }
}
