package com.clubs.payment

import com.clubs.club.ClubRepository
import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SubscriptionLifecycleService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository
) {

    private val log = LoggerFactory.getLogger(SubscriptionLifecycleService::class.java)

    @Transactional(readOnly = true)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findExpiringWithin(now, threshold)

    @Transactional(readOnly = true)
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findActiveExpired(now)

    /**
     * Runs the three DB-mutating lifecycle steps in a single short transaction.
     * External IO (notifications) must be performed outside this method.
     *
     * SELECT-then-UPDATE in step 3 is safe because the scheduler is the single writer
     * of grace_period→expired transitions.
     */
    @Transactional
    fun processExpiry(now: OffsetDateTime) {
        val gracePeriodEnd = now.minusDays(3)

        val moved = membershipRepository.moveActiveToGracePeriod(now)
        if (moved > 0) log.info("Moved {} memberships to grace_period", moved)

        val perClubCounts = membershipRepository.findGracePeriodExpiredGroupedByClub(gracePeriodEnd)
        if (perClubCounts.isNotEmpty()) {
            val fullyExpired = membershipRepository.moveGracePeriodToExpired(gracePeriodEnd)
            perClubCounts.forEach { entry ->
                clubRepository.decrementMemberCountSafely(entry.clubId, entry.count)
            }
            log.info("Expired {} memberships after grace period across {} clubs", fullyExpired, perClubCounts.size)
        }
    }
}
