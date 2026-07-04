package com.clubs.reputation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Надёжная подстраховка для обработки репутации. Слушатель события даёт низкую задержку;
 * этот часовой опрос гарантирует итоговую обработку, если слушатель был пропущен (краш,
 * ошибка AFTER_COMMIT). Каждое событие обрабатывается в собственной транзакции REQUIRES_NEW
 * через (проксированный) бин ReputationService, а атомарный claim делает опрос и слушатель
 * взаимоисключающими.
 */
@Component
class ReputationScheduler(
    private val reputationService: ReputationService,
    private val repository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ReputationScheduler::class.java)

    @Scheduled(fixedDelay = 3_600_000) // каждый час
    fun processPendingFinalizedEvents() {
        val eventIds = repository.findPendingFinalizedEventIds()
        if (eventIds.isEmpty()) return

        var processed = 0
        eventIds.forEach { eventId ->
            try {
                reputationService.processFinalizedEvent(eventId)
                processed++
            } catch (e: Exception) {
                log.error("Failed to process reputation for event {}", eventId, e)
            }
        }
        log.info("Reputation poll: processed {}/{} pending finalized events", processed, eventIds.size)
    }
}
