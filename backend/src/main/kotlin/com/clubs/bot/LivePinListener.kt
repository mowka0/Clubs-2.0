package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.AttendanceMarkedEvent
import com.clubs.event.EventCancelledEvent
import com.clubs.event.EventCreatedEvent
import com.clubs.event.EventRosterChangedEvent
import com.clubs.event.Stage2StartedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * «Живой закреп» (club-chat-link слайс 3): переводит доменные события жизненного цикла события
 * в действия [LivePinService] ПОСЛЕ коммита исходной транзакции (AFTER_COMMIT — как остальные
 * бот-листенеры: перерисовка читает уже закоммиченные голоса/подтверждения). Изменения ростера
 * только ставят dirty-флаг — реальный edit идёт flush-планировщиком с дебаунсом.
 */
@Component
class LivePinListener(
    private val livePinService: LivePinService
) {
    private val log = LoggerFactory.getLogger(LivePinListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onEventCreated(created: EventCreatedEvent) {
        livePinService.onEventCreated(created.event)
    }

    // fallbackExecution: castVote публикует без активной транзакции — событие не должно теряться.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onRosterChanged(changed: EventRosterChangedEvent) {
        livePinService.markDirty(changed.eventId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onStage2Started(started: Stage2StartedEvent) {
        log.info("Live pin: stage 2 started, mark dirty: eventId={}", started.event.id)
        livePinService.markDirty(started.event.id)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onEventCancelled(cancelled: EventCancelledEvent) {
        livePinService.onEventCancelled(cancelled.event, cancelled.reason)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAttendanceMarked(marked: AttendanceMarkedEvent) {
        livePinService.onAttendanceMarked(marked.eventId)
    }
}
