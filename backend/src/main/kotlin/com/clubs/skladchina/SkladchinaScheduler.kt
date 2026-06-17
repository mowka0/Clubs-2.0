package com.clubs.skladchina

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class SkladchinaScheduler(
    private val skladchinaRepository: SkladchinaRepository,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(SkladchinaScheduler::class.java)

    /**
     * Auto-close skladchinas whose deadline has passed. Runs every 10 minutes.
     * Each closure goes through `closeInternal` so reputation hooks + DM are applied.
     * Errors per-skladchina are logged but don't abort the batch.
     */
    @Scheduled(fixedDelay = SCHEDULER_PERIOD_MS)
    fun autoCloseExpired() {
        val expired = skladchinaRepository.findExpiredActive(OffsetDateTime.now())
        if (expired.isEmpty()) return
        log.info("Auto-closing {} expired skladchinas", expired.size)
        expired.forEach { s ->
            try {
                lifecycleService.closeInternal(s.id, closedBy = null, manualClose = false)
            } catch (e: Exception) {
                log.error("Failed to auto-close skladchina ${s.id}", e)
            }
        }
    }

    companion object {
        private const val SCHEDULER_PERIOD_MS = 600_000L  // 10 minutes
    }
}
