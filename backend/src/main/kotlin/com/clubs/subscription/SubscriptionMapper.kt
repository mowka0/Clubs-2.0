package com.clubs.subscription

import com.clubs.generated.jooq.tables.records.PlatformPaymentRecord
import com.clubs.generated.jooq.tables.records.ServiceSubscriptionRecord
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class SubscriptionMapper {

    fun toDomain(record: ServiceSubscriptionRecord): ServiceSubscription = ServiceSubscription(
        id = record.id!!,
        payerUserId = record.payerUserId,
        payerRole = record.payerRole,
        plan = record.plan,
        subjectClubId = record.subjectClubId,
        status = record.status!!,
        currentPeriodEnd = record.currentPeriodEnd,
        providerToken = record.providerToken,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!,
        autopay = record.autopay!!,
        autopayPossible = record.autopayPossible!!,
        chargeAttempts = record.chargeAttempts!!,
        lastChargeAt = record.lastChargeAt,
    )

    fun toPayment(record: PlatformPaymentRecord): PlatformPayment = PlatformPayment(
        id = record.id!!,
        clubId = record.clubId,
        subscriptionId = record.subscriptionId,
        invId = record.invId!!,
        kind = PaymentKind.valueOf(record.kind),
        previousInvId = record.previousInvId,
        amountKopecks = record.amountKopecks,
        status = PlatformPaymentStatus.valueOf(record.status!!),
        autopayRequested = record.autopayRequested!!,
        paymentMethod = record.paymentMethod,
        providerFee = record.providerFee,
        createdAt = record.createdAt!!,
        paidAt = record.paidAt,
    )

    fun toStatusDto(
        state: BillingState,
        priceKopecks: Int,
        subscription: ServiceSubscription?,
        graceUntil: OffsetDateTime?,
        pendingCheckout: Boolean,
    ): BillingStatusDto = BillingStatusDto(
        state = state,
        priceKopecks = priceKopecks,
        currentPeriodEnd = subscription?.currentPeriodEnd,
        graceUntil = graceUntil,
        autopay = subscription?.autopay ?: true,
        autopayPossible = subscription?.autopayPossible ?: false,
        pendingCheckout = pendingCheckout,
    )
}
