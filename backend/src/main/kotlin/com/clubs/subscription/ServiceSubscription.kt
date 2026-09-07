package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Подписка платформы за чат (docs/modules/platform-billing.md). Плоская помесячная: часы идут
 * с оплаты, период 30 дней, никогда не приостанавливается, продлевается автосписанием
 * (ползунок [autopay]) или новой оплатой; заканчивается только в [currentPeriodEnd] + грейс.
 */
data class ServiceSubscription(
    val id: UUID,
    val payerUserId: UUID,
    val payerRole: SubscriptionPayerRole,
    val plan: SubscriptionPlan,
    /** Клуб, за чат которого идёт подписка. null только у завершённых легаси-строк платформенного плана ёмкости (до V87). */
    val subjectClubId: UUID?,
    val status: SubscriptionStatus,
    val currentPeriodEnd: OffsetDateTime,
    /** InvId материнского платежа провайдера (PreviousInvoiceID для дочерних списаний); null до первой оплаты. */
    val providerToken: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    /** Ползунок владельца «Продлевать автоматически» (R9). */
    val autopay: Boolean = true,
    /** Материнский платёж прошёл картой — дочерние списания возможны (Robokassa: рекуррент только по картам). */
    val autopayPossible: Boolean = false,
    /** Дочерних списаний в текущем цикле продления (ретраи в слотах 0/+1/+3 дня от конца периода). */
    val chargeAttempts: Int = 0,
    val lastChargeAt: OffsetDateTime? = null,
) {

    /**
     * Можно ли создавать новые встречи (R10): оплаченный период и грейс после него — да, грейс
     * исчерпан — нет. Считается от [currentPeriodEnd], а не от статуса: шедулер переводит
     * ACTIVE → PAST_DUE раз в сутки, и стена не должна зависеть от его тика.
     */
    fun allowsNewMeetings(now: OffsetDateTime, graceDays: Long): Boolean = when (status) {
        SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE -> now.isBefore(currentPeriodEnd.plusDays(graceDays))
        // Легаси-состояние отмены (ползунком больше не создаётся): доживает период без грейса.
        SubscriptionStatus.CANCELLED_PENDING_END -> now.isBefore(currentPeriodEnd)
        SubscriptionStatus.ENDED -> false
    }
}
