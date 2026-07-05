package com.clubs.bot

import com.clubs.event.EventRepository
import com.clubs.event.WaitlistPromotedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Когда участник повышен из листа ожидания в confirmed (освободился слот), шлём ему DM с кнопкой
 * на страницу события. Реагирует AFTER_COMMIT: [NotificationService.sendWaitlistPromoted] помечен
 * @Async и читает закоммиченное повышение + telegram id на отдельном соединении. Событие несёт
 * только id — событие дозапрашиваем здесь по уже закоммиченному состоянию. Best-effort: ошибки
 * доставки глотаются внутри sendDm. Зеркалит Stage2StartedListener.
 */
@Component
class WaitlistPromotedListener(
    private val eventRepository: EventRepository,
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(WaitlistPromotedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onWaitlistPromoted(event: WaitlistPromotedEvent) {
        val eventEntity = eventRepository.findById(event.eventId)
        if (eventEntity == null) {
            log.warn("Waitlist-promoted DM SKIPPED — event {} not found", event.eventId)
            return
        }
        log.info("Waitlist promoted for event {} — notifying user {}", event.eventId, event.promotedUserId)
        notificationService.sendWaitlistPromoted(eventEntity, event.promotedUserId)
    }
}
