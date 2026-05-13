package com.clubs.application

import com.clubs.club.ClubRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

private const val ACTIVITY_RATING_PENALTY = 5

@Component
class ApplicationScheduler(
    private val applicationRepository: ApplicationRepository,
    private val clubRepository: ClubRepository
) {

    private val log = LoggerFactory.getLogger(ApplicationScheduler::class.java)

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    @Transactional
    fun autoRejectExpiredApplications() {
        val cutoff = OffsetDateTime.now().minusHours(48)
        val expired = applicationRepository.findPendingOlderThan(cutoff)
        if (expired.isEmpty()) return

        applicationRepository.markAutoRejected(cutoff)
        log.info("Auto-rejected ${expired.size} applications: IDs = ${expired.map { it.id }}")

        val clubIds = expired.map { it.clubId }.distinct()
        clubIds.forEach { clubId ->
            clubRepository.decreaseActivityRatingSafely(clubId, ACTIVITY_RATING_PENALTY)
            log.info("Decreased activity_rating by $ACTIVITY_RATING_PENALTY for club $clubId due to auto-rejected applications")
        }
    }
}
