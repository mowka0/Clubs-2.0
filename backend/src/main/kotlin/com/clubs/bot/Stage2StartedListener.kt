package com.clubs.bot

import com.clubs.event.Stage2StartedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * S2T-2: когда событие переходит на Этап 2, отправляем DM голосовавшим «идёт»/«может быть» с
 * просьбой подтвердить участие. Реагирует AFTER_COMMIT, потому что [NotificationService.sendStage2Started]
 * помечен @Async — он читает строки голосов на отдельном соединении, которое увидит переход и
 * назначения waitlist только после коммита транзакции планировщика. Best-effort: ошибки доставки
 * глотаются внутри sendDm, как и для любого другого DM. Зеркалит AttendanceMarkedListener.
 */
@Component
class Stage2StartedListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(Stage2StartedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onStage2Started(event: Stage2StartedEvent) {
        log.info("Stage 2 started for event {} — notifying going/maybe voters", event.event.id)
        notificationService.sendStage2Started(event.event)
    }
}
