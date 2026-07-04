package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class ExpiringSubscriptionNotification(
    val telegramId: Long,
    val clubName: String
)

/**
 * Минимальная проекция строки membership — ровно столько, чтобы платёжные/шедулерные
 * флоу отличили «новую» подписку от «продления», не вытягивая полный jOOQ Record.
 * Полный домен Membership появится при рефакторинге модуля `membership`.
 */
data class MembershipExpiryRef(
    val id: UUID,
    val subscriptionExpiresAt: OffsetDateTime?
)
