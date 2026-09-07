package com.clubs.subscription

import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import java.time.OffsetDateTime
import java.util.UUID

interface SubscriptionRepository {

    /** Новая подписка за чат клуба (план CHAT, плательщик — владелец), сразу ACTIVE: строка рождается с первой оплатой. */
    fun createChatSubscription(
        payerUserId: UUID,
        clubId: UUID,
        currentPeriodEnd: OffsetDateTime,
        providerToken: String?,
        autopay: Boolean,
        autopayPossible: Boolean,
    ): ServiceSubscription

    fun findById(id: UUID): ServiceSubscription?

    /**
     * Подписка клуба с самым поздним оплаченным периодом: живая, если она есть (у неё период
     * всегда позже завершённых), иначе последняя завершённая. null = клуб ни разу не платил.
     */
    fun findLatestByClub(clubId: UUID): ServiceSubscription?

    /** Все живые (ACTIVE/PAST_DUE) подписки за чат — обход шедулера. */
    fun findLive(): List<ServiceSubscription>

    /** Переход статуса только вперёд; возвращает число затронутых строк (0 = параллельное изменение, вызывающий проверяет). */
    fun transitionStatus(id: UUID, from: Collection<SubscriptionStatus>, to: SubscriptionStatus): Int

    fun extendPeriod(id: UUID, newPeriodEnd: OffsetDateTime): Int

    fun updateAutopay(id: UUID, autopay: Boolean): Int

    /** Материнский платёж прошёл: токен для дочерних списаний, ползунок из шита, возможность автосписания; ретраи обнуляются. */
    fun markMotherPaid(id: UUID, providerToken: String, autopay: Boolean, autopayPossible: Boolean): Int

    /** Дочернее списание отправлено провайдеру. */
    fun recordChargeAttempt(id: UUID, at: OffsetDateTime): Int

    /** Продление прошло — цикл ретраев начинается заново. */
    fun resetChargeAttempts(id: UUID): Int

    /** Идемпотентность/дедуп по ключу события (ResultURL, напоминания); false, если [providerEventId] уже был записан. */
    fun recordEventIfNew(subscriptionId: UUID, providerEventId: String, kind: String): Boolean

    /** Текущая цена для [plan] = самая новая строка с effective_from <= now. */
    fun currentPriceKopecks(plan: SubscriptionPlan): Int
}
