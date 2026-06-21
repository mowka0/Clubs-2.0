package com.clubs.clubquality

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodic L3 recompute. The rank is a slow-moving, hidden signal, so a 6-hour full recompute is ample
 * (trivially cheap on a small prod) — no event-driven incrementality needed (YAGNI). Mirrors
 * [com.clubs.reputation.ReputationScheduler]: a failure is logged and the next tick retries.
 */
@Component
class ClubRankScheduler(private val clubRankService: ClubRankService) {

    private val log = LoggerFactory.getLogger(ClubRankScheduler::class.java)

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 21_600_000) // every 6 hours, first run after 6h
    fun recompute() {
        try {
            clubRankService.recomputeAll()
        } catch (e: Exception) {
            log.error("Club-rank recompute failed", e)
        }
    }
}
