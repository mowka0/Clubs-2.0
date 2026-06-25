package com.clubs.clubquality

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodic L3 recompute. The rank is a slow-moving, hidden signal, so a 6-hour full recompute is ample
 * (trivially cheap on a small prod) — no event-driven incrementality needed (YAGNI). Mirrors
 * [com.clubs.reputation.ReputationScheduler]: a failure is logged and the next tick retries.
 *
 * The first run is 5 minutes after startup (not a full 6h): every production redeploy restarts the app
 * and resets the timer, so a 6h initial delay meant the job could rarely fire between frequent deploys.
 * A short initial delay guarantees a recompute shortly after each boot, while still letting the app warm
 * up first (DB pool, caches). The 6h steady-state cadence between runs is unchanged.
 */
@Component
class ClubRankScheduler(private val clubRankService: ClubRankService) {

    private val log = LoggerFactory.getLogger(ClubRankScheduler::class.java)

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 300_000) // first run 5 min after startup, then every 6 hours
    fun recompute() {
        try {
            clubRankService.recomputeAll()
        } catch (e: Exception) {
            log.error("Club-rank recompute failed", e)
        }
    }
}
