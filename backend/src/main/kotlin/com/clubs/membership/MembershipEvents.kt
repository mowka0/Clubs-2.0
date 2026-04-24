package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class ExpiringSubscriptionNotification(
    val telegramId: Long,
    val clubName: String
)

data class ClubMembershipExpiredCount(
    val clubId: UUID,
    val count: Int
)

/**
 * Minimal projection of a membership row — just enough for payment/scheduler
 * flows to decide between "new" and "renewal" without pulling a full jOOQ Record.
 * A full Membership domain will land when the `membership` module is refactored.
 */
data class MembershipExpiryRef(
    val id: UUID,
    val subscriptionExpiresAt: OffsetDateTime?
)
