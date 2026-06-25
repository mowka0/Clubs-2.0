package com.clubs.subscription

import com.clubs.generated.jooq.tables.records.ServiceSubscriptionRecord
import org.springframework.stereotype.Component

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
    )

    fun toStatusDto(subscription: ServiceSubscription, priceKopecks: Int): SubscriptionStatusDto =
        SubscriptionStatusDto(
            plan = subscription.plan.literal,
            status = subscription.status.literal,
            currentPeriodEnd = subscription.currentPeriodEnd,
            maxPaidClubs = SubscriptionPlanPolicy.displayMaxPaidClubs(subscription.plan),
            priceKopecks = priceKopecks,
        )
}
