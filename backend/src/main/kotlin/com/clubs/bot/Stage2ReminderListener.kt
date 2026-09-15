package com.clubs.bot

import com.clubs.event.Stage2ReminderSentEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Ручное напоминание ответить: менеджер жмёт колокольчик у участника, от которого ещё нет ответа.
 * Реагирует AFTER_COMMIT, потому что [NotificationService.sendStage2Reminder] помечен @Async — он
 * читает отметки напоминаний на отдельном соединении, которое увидит их только после коммита.
 * Best-effort: ошибки доставки глотаются внутри sendDm, как и для любого другого DM.
 *
 * Автоматического DM «Этап 2 начался» больше нет: второго этапа не начинается ни у одной новой
 * встречи (docs/modules/event-formats.md § 16.5).
 */
@Component
class Stage2ReminderListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(Stage2ReminderListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStage2ReminderSent(event: Stage2ReminderSentEvent) {
        log.info("Manual Stage 2 reminder for event {} — {} recipient(s)", event.event.id, event.telegramIds.size)
        notificationService.sendStage2Reminder(event.event, event.telegramIds, event.rosterDeadline)
    }
}
