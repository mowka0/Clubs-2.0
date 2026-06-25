package com.clubs.payment

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Seam over the recurring service-fee acquirer — the platform's OWN fee (docs/modules/payment-v2.md §5.4).
 * Stub now (no real money: needs ИП + ЮKassa/Т-Банк, a legal block). A real acquirer drops in later as
 * a second @Component without touching the subscription engine. The amount is always computed server-side.
 */
interface PaymentProvider {

    /** Open a recurring charge for the platform's service fee. Returns the provider-side handle + first period end. */
    fun createSubscription(command: CreateSubscriptionCommand): ProviderSubscription

    /** Stop auto-renewal at the provider. Idempotent — safe to call on an already-cancelled token. */
    fun cancelSubscription(providerToken: String?)

    /** Parse + verify an inbound provider webhook (signature check lives in the impl). */
    fun parseWebhook(rawBody: String, signature: String?): WebhookResult
}

data class CreateSubscriptionCommand(
    val payerUserId: UUID,
    val payerRole: SubscriptionPayerRole,
    val plan: SubscriptionPlan,
    val priceKopecks: Int,
    val subjectClubId: UUID?,
)

data class ProviderSubscription(
    val providerToken: String?,
    val currentPeriodEnd: OffsetDateTime,
)

/** Outcome of an inbound webhook. The engine maps it to a forward-only state transition. */
sealed interface WebhookResult {
    /** A recurring charge succeeded → extend the paid period and recover from any dunning. */
    data class RenewalSucceeded(
        val providerEventId: String,
        val providerToken: String,
        val newPeriodEnd: OffsetDateTime,
    ) : WebhookResult

    /** A recurring charge failed → enter dunning (PAST_DUE). */
    data class RenewalFailed(
        val providerEventId: String,
        val providerToken: String,
    ) : WebhookResult

    /** Not actionable (unknown event, stub provider, etc.). */
    data class Ignored(val reason: String) : WebhookResult
}
