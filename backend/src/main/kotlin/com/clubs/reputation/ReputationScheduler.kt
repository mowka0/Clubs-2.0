package com.clubs.reputation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Durable backstop for reputation processing. The event listener gives low latency;
 * this hourly poll guarantees eventual processing if a listener was missed (crash,
 * AFTER_COMMIT failure). Each event is processed in its own REQUIRES_NEW transaction
 * via the (proxied) ReputationService bean, and the atomic claim makes poll and
 * listener mutually exclusive.
 */
@Component
class ReputationScheduler(
    private val reputationService: ReputationService,
    private val repository: ReputationRepository
) {

    private val log = LoggerFactory.getLogger(ReputationScheduler::class.java)

    @Scheduled(fixedDelay = 3_600_000) // every hour
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
