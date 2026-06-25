package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.generated.jooq.tables.references.SERVICE_SUBSCRIPTION
import com.clubs.generated.jooq.tables.references.SUBSCRIPTION_EVENT
import com.clubs.generated.jooq.tables.references.SUBSCRIPTION_PRICING
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqSubscriptionRepository(
    private val dsl: DSLContext,
    private val mapper: SubscriptionMapper,
) : SubscriptionRepository {

    override fun create(
        payerUserId: UUID,
        payerRole: SubscriptionPayerRole,
        plan: SubscriptionPlan,
        subjectClubId: UUID?,
        currentPeriodEnd: OffsetDateTime,
        providerToken: String?,
    ): ServiceSubscription {
        val record = dsl.insertInto(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.ID, UUID.randomUUID())
            .set(SERVICE_SUBSCRIPTION.PAYER_USER_ID, payerUserId)
            .set(SERVICE_SUBSCRIPTION.PAYER_ROLE, payerRole)
            .set(SERVICE_SUBSCRIPTION.PLAN, plan)
            .set(SERVICE_SUBSCRIPTION.SUBJECT_CLUB_ID, subjectClubId)
            .set(SERVICE_SUBSCRIPTION.STATUS, SubscriptionStatus.ACTIVE)
            .set(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END, currentPeriodEnd)
            .set(SERVICE_SUBSCRIPTION.PROVIDER_TOKEN, providerToken)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findActiveOrganizerSubscription(payerUserId: UUID): ServiceSubscription? =
        dsl.selectFrom(SERVICE_SUBSCRIPTION)
            .where(
                SERVICE_SUBSCRIPTION.PAYER_USER_ID.eq(payerUserId)
                    .and(SERVICE_SUBSCRIPTION.PAYER_ROLE.eq(SubscriptionPayerRole.ORGANIZER))
                    .and(SERVICE_SUBSCRIPTION.STATUS.ne(SubscriptionStatus.ENDED)),
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByProviderToken(providerToken: String): ServiceSubscription? =
        dsl.selectFrom(SERVICE_SUBSCRIPTION)
            .where(
                SERVICE_SUBSCRIPTION.PROVIDER_TOKEN.eq(providerToken)
                    .and(SERVICE_SUBSCRIPTION.STATUS.ne(SubscriptionStatus.ENDED)),
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun updatePlan(id: UUID, plan: SubscriptionPlan): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.PLAN, plan)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
            .execute()

    override fun transitionStatus(id: UUID, from: Collection<SubscriptionStatus>, to: SubscriptionStatus): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.STATUS, to)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id).and(SERVICE_SUBSCRIPTION.STATUS.`in`(from)))
            .execute()

    override fun extendPeriod(id: UUID, newPeriodEnd: OffsetDateTime): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END, newPeriodEnd)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
            .execute()

    override fun endElapsedCancelled(now: OffsetDateTime): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.STATUS, SubscriptionStatus.ENDED)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, now)
            .where(
                SERVICE_SUBSCRIPTION.STATUS.eq(SubscriptionStatus.CANCELLED_PENDING_END)
                    .and(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END.le(now)),
            )
            .execute()

    override fun recordEventIfNew(subscriptionId: UUID, providerEventId: String, kind: String): Boolean =
        dsl.insertInto(SUBSCRIPTION_EVENT)
            .set(SUBSCRIPTION_EVENT.ID, UUID.randomUUID())
            .set(SUBSCRIPTION_EVENT.SUBSCRIPTION_ID, subscriptionId)
            .set(SUBSCRIPTION_EVENT.PROVIDER_EVENT_ID, providerEventId)
            .set(SUBSCRIPTION_EVENT.KIND, kind)
            .onConflict(SUBSCRIPTION_EVENT.PROVIDER_EVENT_ID)
            .doNothing()
            .execute() > 0

    override fun currentPriceKopecks(plan: SubscriptionPlan): Int =
        dsl.select(SUBSCRIPTION_PRICING.PRICE_KOPECKS)
            .from(SUBSCRIPTION_PRICING)
            .where(
                SUBSCRIPTION_PRICING.PLAN.eq(plan)
                    .and(SUBSCRIPTION_PRICING.EFFECTIVE_FROM.le(OffsetDateTime.now())),
            )
            .orderBy(SUBSCRIPTION_PRICING.EFFECTIVE_FROM.desc())
            .limit(1)
            .fetchOne(SUBSCRIPTION_PRICING.PRICE_KOPECKS)
            ?: throw IllegalStateException("No pricing configured for plan $plan")
}
