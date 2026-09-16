package com.clubs.payment

import java.math.BigDecimal
import java.util.UUID

/**
 * Шов над провайдером платежей платформы за чат (docs/modules/platform-billing.md § 6.1).
 * Деньги владельца клуба → платформе (самозанятому); взносы участник → организатор идут мимо
 * (payment.md). Сумму всегда считает сервер из subscription_pricing — клиент цену не передаёт.
 *
 * Реализации: [StubPaymentProvider] (dev/staging без ключей) и RobokassaPaymentProvider
 * (`billing.provider=robokassa`). Движок подписок ([com.clubs.subscription.BillingService]) от
 * выбора провайдера не зависит.
 */
interface PaymentProvider {

    /** Короткий ключ провайдера — префикс идемпотентности вебхука (`<id>:paid:<invId>`). */
    val id: String

    /** Ссылка на страницу оплаты материнского платежа. `recurring=true` разрешает дочерние списания. */
    fun createCheckout(request: CheckoutRequest): CheckoutUrl

    /** Дочернее списание по сохранённой карте. Возвращает факт ПРИЁМА заявки, не факт списания. */
    fun charge(request: RecurringChargeRequest): ChargeAccepted

    /** Пришёл ли ResultURL с адреса провайдера (allowlist). Stub доверяет всем. */
    fun trustsResultSource(clientIp: String): Boolean

    /**
     * ResultURL: проверка подписи и маппинг form-параметров в результат. Неверная подпись →
     * [com.clubs.common.exception.ForbiddenException].
     */
    fun parseResultNotification(params: Map<String, String>): ResultNotification

    /** Опрос состояния операции (для дочерних платежей и материнских без ResultURL). */
    fun queryState(invId: Long): PaymentStateResult
}

data class CheckoutRequest(
    val invId: Long,
    val amountKopecks: Int,
    /** ≤ 100 символов у Robokassa — адаптер обрезает сам. */
    val description: String,
    val recurring: Boolean,
    /** Прокидывается провайдеру как пользовательский параметр и возвращается в ResultURL. */
    val clubId: UUID,
    val successUrl: String,
    val failUrl: String,
)

@JvmInline
value class CheckoutUrl(val value: String)

data class RecurringChargeRequest(
    val invId: Long,
    /** InvId материнского платежа (PreviousInvoiceID). */
    val previousInvId: Long,
    val amountKopecks: Int,
    val description: String,
    val clubId: UUID,
)

data class ChargeAccepted(val accepted: Boolean, val providerMessage: String? = null)

data class ResultNotification(
    val invId: Long,
    val amountKopecks: Int,
    /** Способ оплаты у провайдера (BankCard, SBP, …); null, если провайдер его не сообщил. */
    val paymentMethod: String?,
    /** Комиссия провайдера в рублях — только для сверки. */
    val fee: BigDecimal?,
)

enum class PaymentState { PENDING, SUCCEEDED, FAILED }

data class PaymentStateResult(val state: PaymentState, val paymentMethod: String? = null)
