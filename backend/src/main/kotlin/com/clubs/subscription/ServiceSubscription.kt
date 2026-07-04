package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Подписка на сервисный сбор платформы (docs/modules/payment-v2.md §5.1). Плоская помесячная:
 * действует весь оплаченный период независимо от активности клуба, никогда не приостанавливается,
 * заканчивается только в [currentPeriodEnd].
 */
data class ServiceSubscription(
    val id: UUID,
    val payerUserId: UUID,
    val payerRole: SubscriptionPayerRole,
    val plan: SubscriptionPlan,
    /** NULL = платформенный план ёмкости организатора; привязан к клубу для member-pays (фаза 2). */
    val subjectClubId: UUID?,
    val status: SubscriptionStatus,
    val currentPeriodEnd: OffsetDateTime,
    val providerToken: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
