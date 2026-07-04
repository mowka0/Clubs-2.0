package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SubscriptionRepository {

    fun create(
        payerUserId: UUID,
        payerRole: SubscriptionPayerRole,
        plan: SubscriptionPlan,
        subjectClubId: UUID?,
        currentPeriodEnd: OffsetDateTime,
        providerToken: String?,
    ): ServiceSubscription

    /** Действующий (не ENDED) платформенный план организатора, или null при неявном FREE. */
    fun findActiveOrganizerSubscription(payerUserId: UUID): ServiceSubscription?

    fun findByProviderToken(providerToken: String): ServiceSubscription?

    fun updatePlan(id: UUID, plan: SubscriptionPlan): Int

    /** Переход статуса только вперёд; возвращает число затронутых строк (0 = параллельное изменение, вызывающий проверяет). */
    fun transitionStatus(id: UUID, from: Collection<SubscriptionStatus>, to: SubscriptionStatus): Int

    fun extendPeriod(id: UUID, newPeriodEnd: OffsetDateTime): Int

    /** Строки CANCELLED_PENDING_END, у которых истёк период → ENDED. Возвращает количество. */
    fun endElapsedCancelled(now: OffsetDateTime): Int

    /** Идемпотентность входящего вебхука: вставляет событие; false, если [providerEventId] уже был обработан. */
    fun recordEventIfNew(subscriptionId: UUID, providerEventId: String, kind: String): Boolean

    /** Текущая цена для [plan] = самая новая строка с effective_from <= now. */
    fun currentPriceKopecks(plan: SubscriptionPlan): Int
}
