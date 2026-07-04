package com.clubs.bot

import com.clubs.event.AttendanceMarkedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * ATT-3: после того как организатор отметил явку, шлём DM участникам с отметкой absent, чтобы они
 * могли оспорить. Реагирует AFTER_COMMIT, потому что [com.clubs.bot.NotificationService.sendAttendanceMarked]
 * — @Async: он читает только что записанные `absent`-строки на отдельном соединении, которое видит
 * их лишь после коммита транзакции markAttendance. Best-effort: ошибки доставки глотаются внутри
 * sendDm, как и у любого другого DM. Зеркалит AttendanceFinalizedListener.
 */
@Component
class AttendanceMarkedListener(
    private val notificationService: NotificationService
) {

    private val log = LoggerFactory.getLogger(AttendanceMarkedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAttendanceMarked(event: AttendanceMarkedEvent) {
        log.info("Attendance marked for event {} — notifying {} newly-absent participant(s)", event.eventId, event.newlyAbsentUserIds.size)
        notificationService.sendAttendanceMarked(event.eventId, event.newlyAbsentUserIds)
    }
}
