package com.clubs.bot

import com.clubs.event.Stage2ReminderSentEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Отправляет DM-напоминание подтвердить участие после того, как менеджер нажал колокольчик.
 * AFTER_COMMIT по той же причине, что и [Stage2StartedListener]: @Async-отправка не должна
 * обгонять коммит отметки `stage2_reminded_at`. Best-effort — ошибки доставки глотает sendDm.
 */
@Component
class Stage2ReminderListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(Stage2ReminderListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStage2ReminderSent(event: Stage2ReminderSentEvent) {
        log.info(
            "Manual Stage 2 reminder for event {} — notifying {} member(s)",
            event.event.id, event.telegramIds.size
        )
        notificationService.sendStage2Reminder(event.event, event.telegramIds)
    }
}
