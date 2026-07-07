package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

data class ExpiringSubscriptionNotification(
    val telegramId: Long,
    val clubName: String,
    // Для inline-кнопки-диплинка в DM «подписка истекла» (открывает /clubs/{id}, где живёт «Оплатить взнос»).
    val clubId: UUID
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
