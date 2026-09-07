package com.clubs.subscription

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

interface PlatformPaymentRepository {

    /** Новый счёт; InvId выдаёт последовательность БД. */
    fun create(
        clubId: UUID,
        subscriptionId: UUID?,
        kind: PaymentKind,
        amountKopecks: Int,
        previousInvId: Long?,
        autopayRequested: Boolean,
    ): PlatformPayment

    fun findByInvId(invId: Long): PlatformPayment?

    /** Последний неоплаченный материнский счёт клуба, выставленный не раньше [createdAfter] — для идемпотентного чекаута. */
    fun findPendingMother(clubId: UUID, createdAfter: OffsetDateTime): PlatformPayment?

    /** Есть ли у подписки дочернее списание, ещё не получившее ответа, — второе не выставляем. */
    fun hasPendingRecurring(subscriptionId: UUID): Boolean

    /**
     * PENDING → SUCCEEDED атомарно: 0 строк = счёт уже подтверждён или отклонён (идемпотентность
     * ResultURL строится на этом, а не на отдельном флаге).
     */
    fun markSucceeded(id: UUID, paymentMethod: String?, providerFee: BigDecimal?, paidAt: OffsetDateTime): Int

    /** Подписка, к которой отнесён счёт, становится известна при первой оплате. */
    fun attachSubscription(id: UUID, subscriptionId: UUID): Int

    fun markFailed(id: UUID): Int

    /** Неоплаченные счета старше [cutoff] — кандидаты на опрос состояния у провайдера. */
    fun findPendingCreatedBefore(cutoff: OffsetDateTime): List<PlatformPayment>
}
