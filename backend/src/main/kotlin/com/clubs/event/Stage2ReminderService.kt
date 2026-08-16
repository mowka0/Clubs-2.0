package com.clubs.event

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.EventStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ручное напоминание подтвердить участие на Этапе 2 (event-stage2-composition.md).
 * Менеджер жмёт колокольчик у не ответившего — участник получает DM с кнопкой подтверждения.
 *
 * Отдельный сервис, а не метод [Stage2Service]: тот ведёт сам жизненный цикл Этапа 2 (переход,
 * подтверждение, отказ, продвижение очереди, авто-истечение) и уже на верхней границе разумного
 * размера; напоминания — своя ответственность со своими правилами доступа.
 *
 * Автоматическая рассылка «подтвердите за 2 часа до встречи» здесь НЕ восстанавливается: её
 * удалили как лишний пинг (V51, решение PO 2026-07-08). Инициатор всегда человек.
 */
@Service
class Stage2ReminderService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val eventPublisher: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(Stage2ReminderService::class.java)

    /**
     * Напоминает одному участнику ([targetUserId]) либо всем, кому ещё можно (`null`).
     * Возвращает число реально отправленных напоминаний: повторный тап по тому же участнику
     * даёт 0 — фильтр живёт в самом UPDATE (`markStage2Reminded`), поэтому и гонка двух
     * менеджеров не приведёт к двойному DM.
     */
    @Transactional
    fun remind(eventId: UUID, actorId: UUID, targetUserId: UUID?): RemindResultDto {
        val event = eventRepository.findById(eventId) ?: throw NotFoundException("Event not found")
        val club = clubRepository.findById(event.clubId) ?: throw NotFoundException("Club not found")
        // Менеджерский гейт (co-organizers): владелец или активный со-орг. Тот же, что у отметки явки.
        clubRoleGuard.requireCapability(club, actorId, ClubCapability.MANAGE_EVENTS)

        // Окно то же, в котором участник может подтвердить (зеркалит Stage2Service.confirmParticipation):
        // до Этапа 2 подтверждать нечего, после старта встречи напоминание уже бессмысленно.
        if (event.status != EventStatus.stage_2) {
            throw ValidationException("Confirmation is not open for this event")
        }
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) {
            throw ValidationException("Event has already started")
        }

        val targets = targetUserId?.let { listOf(it) }
            ?: eventResponseRepository.findStage2RemindableUserIds(eventId)
        val telegramIds = eventResponseRepository.markStage2Reminded(eventId, targets)

        if (telegramIds.isNotEmpty()) {
            // DM уходит AFTER_COMMIT (Stage2ReminderListener): уведомление без закоммиченной
            // отметки означало бы, что следующий тап пришлёт участнику второе напоминание.
            eventPublisher.publishEvent(Stage2ReminderSentEvent(event, telegramIds))
        }
        log.info(
            "Stage 2 reminder: eventId={} actorId={} targeted={} reminded={}",
            eventId, actorId, targets.size, telegramIds.size
        )
        return RemindResultDto(remindedCount = telegramIds.size)
    }
}
