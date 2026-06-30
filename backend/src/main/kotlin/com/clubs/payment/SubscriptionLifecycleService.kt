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
     * Honor-system auto-expiry (de-Stars Slice 2): in a single short transaction, every `active` paid
     * membership whose access window (subscription_expires_at) has passed drops to `frozen` ("ждёт
     * оплаты") — the member keeps belonging but loses content access until the organizer confirms the
     * next dues payment. Free memberships (no expiry) are untouched. External IO (notifications) must be
     * performed outside this method. Hard cut, no grace period — by PO decision (de-Stars).
     */
    @Transactional
    fun processExpiry(now: OffsetDateTime) {
        val expired = membershipRepository.expireOverdueAccess(now)
        if (expired > 0) log.info("Auto-expired {} overdue memberships to frozen (awaiting dues)", expired)
    }
}
