package com.clubs.payment

import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Шов над эквайером рекуррентного сервисного сбора — СОБСТВЕННОЙ комиссии платформы
 * (docs/modules/payment-v2.md §5.4). Сейчас стаб (реальных денег нет: нужны ИП + ЮKassa/Т-Банк,
 * юридический блок). Реальный эквайер подключится позже вторым @Component, не трогая движок
 * подписок. Сумма всегда считается на сервере.
 */
interface PaymentProvider {

    /** Открывает рекуррентное списание сервисного сбора платформы. Возвращает хендл провайдера + конец первого периода. */
    fun createSubscription(command: CreateSubscriptionCommand): ProviderSubscription

    /** Останавливает авто-продление у провайдера. Идемпотентно — безопасно вызывать на уже отменённом токене. */
    fun cancelSubscription(providerToken: String?)

    /** Парсит + верифицирует входящий webhook провайдера (проверка подписи — в реализации). */
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

/** Результат входящего webhook. Движок маппит его на переход состояния только вперёд (forward-only). */
sealed interface WebhookResult {
    /** Рекуррентное списание прошло успешно → продлить оплаченный период и выйти из dunning, если был. */
    data class RenewalSucceeded(
        val providerEventId: String,
        val providerToken: String,
        val newPeriodEnd: OffsetDateTime,
    ) : WebhookResult

    /** Рекуррентное списание не прошло → войти в dunning (PAST_DUE). */
    data class RenewalFailed(
        val providerEventId: String,
        val providerToken: String,
    ) : WebhookResult

    /** Не требует действия (неизвестное событие, стаб-провайдер и т.п.). */
    data class Ignored(val reason: String) : WebhookResult
}
