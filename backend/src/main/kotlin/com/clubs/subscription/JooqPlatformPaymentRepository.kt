package com.clubs.subscription

import com.clubs.generated.jooq.tables.references.PLATFORM_PAYMENT
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqPlatformPaymentRepository(
    private val dsl: DSLContext,
    private val mapper: SubscriptionMapper,
) : PlatformPaymentRepository {

    override fun create(
        clubId: UUID,
        subscriptionId: UUID?,
        kind: PaymentKind,
        amountKopecks: Int,
        previousInvId: Long?,
        autopayRequested: Boolean,
    ): PlatformPayment {
        val record = dsl.insertInto(PLATFORM_PAYMENT)
            .set(PLATFORM_PAYMENT.CLUB_ID, clubId)
            .set(PLATFORM_PAYMENT.SUBSCRIPTION_ID, subscriptionId)
            .set(PLATFORM_PAYMENT.KIND, kind.name)
            .set(PLATFORM_PAYMENT.PREVIOUS_INV_ID, previousInvId)
            .set(PLATFORM_PAYMENT.AMOUNT_KOPECKS, amountKopecks)
            .set(PLATFORM_PAYMENT.AUTOPAY_REQUESTED, autopayRequested)
            .returning()
            .fetchOne()!!
        return mapper.toPayment(record)
    }

    override fun findByInvId(invId: Long): PlatformPayment? =
        dsl.selectFrom(PLATFORM_PAYMENT).where(PLATFORM_PAYMENT.INV_ID.eq(invId)).fetchOne()?.let(mapper::toPayment)

    override fun findPendingMother(clubId: UUID, createdAfter: OffsetDateTime): PlatformPayment? =
        dsl.selectFrom(PLATFORM_PAYMENT)
            .where(
                PLATFORM_PAYMENT.CLUB_ID.eq(clubId)
                    .and(PLATFORM_PAYMENT.KIND.eq(PaymentKind.MOTHER.name))
                    .and(PLATFORM_PAYMENT.STATUS.eq(PlatformPaymentStatus.PENDING.name))
                    .and(PLATFORM_PAYMENT.CREATED_AT.ge(createdAfter)),
            )
            .orderBy(PLATFORM_PAYMENT.CREATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.let(mapper::toPayment)

    override fun hasPendingRecurring(subscriptionId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(PLATFORM_PAYMENT).where(
                PLATFORM_PAYMENT.SUBSCRIPTION_ID.eq(subscriptionId)
                    .and(PLATFORM_PAYMENT.KIND.eq(PaymentKind.RECURRING.name))
                    .and(PLATFORM_PAYMENT.STATUS.eq(PlatformPaymentStatus.PENDING.name)),
            ),
        )

    override fun markSucceeded(id: UUID, paymentMethod: String?, providerFee: BigDecimal?, paidAt: OffsetDateTime): Int =
        dsl.update(PLATFORM_PAYMENT)
            .set(PLATFORM_PAYMENT.STATUS, PlatformPaymentStatus.SUCCEEDED.name)
            .set(PLATFORM_PAYMENT.PAYMENT_METHOD, paymentMethod)
            .set(PLATFORM_PAYMENT.PROVIDER_FEE, providerFee)
            .set(PLATFORM_PAYMENT.PAID_AT, paidAt)
            .where(PLATFORM_PAYMENT.ID.eq(id).and(PLATFORM_PAYMENT.STATUS.eq(PlatformPaymentStatus.PENDING.name)))
            .execute()

    override fun attachSubscription(id: UUID, subscriptionId: UUID): Int =
        dsl.update(PLATFORM_PAYMENT)
            .set(PLATFORM_PAYMENT.SUBSCRIPTION_ID, subscriptionId)
            .where(PLATFORM_PAYMENT.ID.eq(id))
            .execute()

    override fun markFailed(id: UUID): Int =
        dsl.update(PLATFORM_PAYMENT)
            .set(PLATFORM_PAYMENT.STATUS, PlatformPaymentStatus.FAILED.name)
            .where(PLATFORM_PAYMENT.ID.eq(id).and(PLATFORM_PAYMENT.STATUS.eq(PlatformPaymentStatus.PENDING.name)))
            .execute()

    override fun findPendingCreatedBefore(cutoff: OffsetDateTime): List<PlatformPayment> =
        dsl.selectFrom(PLATFORM_PAYMENT)
            .where(PLATFORM_PAYMENT.STATUS.eq(PlatformPaymentStatus.PENDING.name).and(PLATFORM_PAYMENT.CREATED_AT.lt(cutoff)))
            .orderBy(PLATFORM_PAYMENT.CREATED_AT.asc())
            .fetch()
            .map(mapper::toPayment)
}
