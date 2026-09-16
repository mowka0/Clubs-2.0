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

    /**
     * Есть ли у клуба неоплаченный материнский счёт ЛЮБОГО возраста. Статус для шита считается по
     * нему, а не по окну переиспользования: иначе счёт старше 30 минут выглядел бы как «оплачено»
     * и шит поздравлял бы с несостоявшимся продлением (ревью 2026-09-07).
     */
    fun hasPendingMother(clubId: UUID): Boolean

    /** Ползунок автопродления переехал на существующий счёт: повторный чекаут меняет решение владельца. */
    fun updateAutopayRequested(id: UUID, autopayRequested: Boolean): Int

    /** Есть ли у подписки дочернее списание, ещё не получившее ответа, — второе не выставляем. */
    fun hasPendingRecurring(subscriptionId: UUID): Boolean

    /**
     * Перевод счёта в SUCCEEDED атомарно: 0 строк = он уже был подтверждён (идемпотентность
     * ResultURL строится на этом, а не на отдельном флаге). Счёт, закрытый по таймауту как FAILED,
     * подтвердить МОЖНО: ссылка оплаты у провайдера не истекает, и поздняя оплата должна дойти,
     * а не потеряться вместе с деньгами (ревью 2026-09-07).
     */
    fun markSucceeded(id: UUID, paymentMethod: String?, providerFee: BigDecimal?, paidAt: OffsetDateTime): Int

    /** Подписка, к которой отнесён счёт, становится известна при первой оплате. */
    fun attachSubscription(id: UUID, subscriptionId: UUID): Int

    fun markFailed(id: UUID): Int

    /** Неоплаченные счета старше [cutoff] — кандидаты на опрос состояния у провайдера. */
    fun findPendingCreatedBefore(cutoff: OffsetDateTime): List<PlatformPayment>
}
