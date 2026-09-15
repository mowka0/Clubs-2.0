package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.AttendanceMarkedEvent
import com.clubs.event.EventCancelledEvent
import com.clubs.event.EventCreatedEvent
import com.clubs.event.EventRosterChangedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * «Живой закреп» (club-chat-link слайс 3): переводит доменные события жизненного цикла события
 * в действия [LivePinService] ПОСЛЕ коммита исходной транзакции (AFTER_COMMIT — как остальные
 * бот-листенеры: перерисовка читает уже закоммиченные голоса/подтверждения). Изменения ростера
 * только ставят dirty-флаг — реальный edit идёт flush-планировщиком с дебаунсом.
 *
 * Создание и отмена события здесь НЕ слушаются: их оркестрирует [EventBotNotifier] /
 * [EventCancelledListener] — чат-пост и DM-рассылка связаны маршрутизатором
 * ([ChatAwareBroadcast]: DM только тем, кого пост в чате не покрыл), поэтому обязаны
 * идти последовательно в одном потоке, а не гоняться двумя листенерами.
 */
@Component
class LivePinListener(
    private val livePinService: LivePinService
) {
    // fallbackExecution: castVote публикует без активной транзакции — событие не должно теряться.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onRosterChanged(changed: EventRosterChangedEvent) {
        livePinService.markDirty(changed.eventId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAttendanceMarked(marked: AttendanceMarkedEvent) {
        livePinService.onAttendanceMarked(marked.eventId)
    }
}
