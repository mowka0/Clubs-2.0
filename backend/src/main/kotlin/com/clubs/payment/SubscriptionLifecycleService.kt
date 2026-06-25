package com.clubs.payment

import com.clubs.membership.ExpiringSubscriptionNotification
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class SubscriptionLifecycleService(
    private val membershipRepository: MembershipRepository
) {

    private val log = LoggerFactory.getLogger(SubscriptionLifecycleService::class.java)

    @Transactional(readOnly = true)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findExpiringWithin(now, threshold)

    @Transactional(readOnly = true)
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        membershipRepository.findActiveExpired(now)

    /**
     * Runs the two DB-mutating lifecycle steps in a single short transaction:
     * active→grace_period, then grace_period→expired. External IO (notifications)
     * must be performed outside this method.
     *
     * The grace_period→expired UPDATE is safe to run unconditionally because the
     * scheduler is the single writer of that transition.
     */
    @Transactional
    fun processExpiry(now: OffsetDateTime) {
        val gracePeriodEnd = now.minusDays(3)

        val moved = membershipRepository.moveActiveToGracePeriod(now)
        if (moved > 0) log.info("Moved {} memberships to grace_period", moved)

        val fullyExpired = membershipRepository.moveGracePeriodToExpired(gracePeriodEnd)
        if (fullyExpired > 0) log.info("Expired {} memberships after grace period", fullyExpired)
    }
}
