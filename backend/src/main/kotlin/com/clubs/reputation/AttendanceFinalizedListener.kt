package com.clubs.reputation

import com.clubs.event.AttendanceFinalizedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Обработка репутации с низкой задержкой: реагирует на AttendanceFinalizedEvent ПОСЛЕ
 * коммита транзакции финализации, в собственной транзакции REQUIRES_NEW (внутри
 * ReputationService). Best-effort — ошибка здесь логируется и восстанавливается часовым
 * опросом (ReputationScheduler). Намеренно отвязано от финализации: ошибка репутации
 * никогда не должна откатывать финализацию явки.
 */
@Component
class AttendanceFinalizedListener(
    private val reputationService: ReputationService
) {

    private val log = LoggerFactory.getLogger(AttendanceFinalizedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAttendanceFinalized(event: AttendanceFinalizedEvent) {
        try {
            reputationService.processFinalizedEvent(event.eventId)
        } catch (e: Exception) {
            log.error("Live reputation processing failed for event {} (poll will retry)", event.eventId, e)
        }
    }
}
