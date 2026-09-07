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

    override fun createChatSubscription(
        payerUserId: UUID,
        clubId: UUID,
        currentPeriodEnd: OffsetDateTime,
        providerToken: String?,
        autopay: Boolean,
        autopayPossible: Boolean,
    ): ServiceSubscription {
        val record = dsl.insertInto(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.ID, UUID.randomUUID())
            .set(SERVICE_SUBSCRIPTION.PAYER_USER_ID, payerUserId)
            .set(SERVICE_SUBSCRIPTION.PAYER_ROLE, SubscriptionPayerRole.ORGANIZER)
            .set(SERVICE_SUBSCRIPTION.PLAN, SubscriptionPlan.CHAT)
            .set(SERVICE_SUBSCRIPTION.SUBJECT_CLUB_ID, clubId)
            .set(SERVICE_SUBSCRIPTION.STATUS, SubscriptionStatus.ACTIVE)
            .set(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END, currentPeriodEnd)
            .set(SERVICE_SUBSCRIPTION.PROVIDER_TOKEN, providerToken)
            .set(SERVICE_SUBSCRIPTION.AUTOPAY, autopay)
            .set(SERVICE_SUBSCRIPTION.AUTOPAY_POSSIBLE, autopayPossible)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): ServiceSubscription? =
        dsl.selectFrom(SERVICE_SUBSCRIPTION).where(SERVICE_SUBSCRIPTION.ID.eq(id)).fetchOne()?.let(mapper::toDomain)

    override fun findLatestByClub(clubId: UUID): ServiceSubscription? =
        dsl.selectFrom(SERVICE_SUBSCRIPTION)
            .where(
                SERVICE_SUBSCRIPTION.SUBJECT_CLUB_ID.eq(clubId)
                    .and(SERVICE_SUBSCRIPTION.PAYER_ROLE.eq(SubscriptionPayerRole.ORGANIZER)),
            )
            .orderBy(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END.desc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findLive(): List<ServiceSubscription> =
        dsl.selectFrom(SERVICE_SUBSCRIPTION)
            .where(
                SERVICE_SUBSCRIPTION.PAYER_ROLE.eq(SubscriptionPayerRole.ORGANIZER)
                    .and(SERVICE_SUBSCRIPTION.STATUS.`in`(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)),
            )
            .orderBy(SERVICE_SUBSCRIPTION.CURRENT_PERIOD_END.asc())
            .fetch()
            .map(mapper::toDomain)

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

    override fun updateAutopay(id: UUID, autopay: Boolean): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.AUTOPAY, autopay)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
            .execute()

    override fun markMotherPaid(id: UUID, providerToken: String, autopay: Boolean, autopayPossible: Boolean): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.PROVIDER_TOKEN, providerToken)
            .set(SERVICE_SUBSCRIPTION.AUTOPAY, autopay)
            .set(SERVICE_SUBSCRIPTION.AUTOPAY_POSSIBLE, autopayPossible)
            .set(SERVICE_SUBSCRIPTION.CHARGE_ATTEMPTS, 0)
            .setNull(SERVICE_SUBSCRIPTION.LAST_CHARGE_AT)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
            .execute()

    override fun recordChargeAttempt(id: UUID, at: OffsetDateTime): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.CHARGE_ATTEMPTS, SERVICE_SUBSCRIPTION.CHARGE_ATTEMPTS.plus(1))
            .set(SERVICE_SUBSCRIPTION.LAST_CHARGE_AT, at)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, at)
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
            .execute()

    override fun resetChargeAttempts(id: UUID): Int =
        dsl.update(SERVICE_SUBSCRIPTION)
            .set(SERVICE_SUBSCRIPTION.CHARGE_ATTEMPTS, 0)
            .setNull(SERVICE_SUBSCRIPTION.LAST_CHARGE_AT)
            .set(SERVICE_SUBSCRIPTION.UPDATED_AT, OffsetDateTime.now())
            .where(SERVICE_SUBSCRIPTION.ID.eq(id))
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
