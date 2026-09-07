package com.clubs.payment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Провайдер-заглушка для dev/staging без ключей Robokassa: реальных денег не двигает, но весь
 * цикл проходит. Материнский платёж «оплачивается» переходом по ссылке чекаута — она ведёт на
 * `/api/billing/stub/pay`, который отрабатывает как ResultURL и уводит на страницу возврата.
 * Дочернее списание принимается всегда и через [settleSeconds] отвечает SUCCEEDED на опрос,
 * так что напоминания, PAST_DUE, грейс и ретраи шедулера проверяются на staging целиком.
 */
@Component
@ConditionalOnProperty(name = ["billing.provider"], havingValue = "stub", matchIfMissing = true)
class StubPaymentProvider(
    // Публичный базовый URL приложения: ссылка чекаута открывается во внешнем браузере.
    @Value("\${telegram.webapp-base-url}") private val webAppBaseUrl: String,
    // Через сколько секунд после приёма дочернее списание считается прошедшим.
    @Value("\${billing.stub.settle-seconds:5}") private val settleSeconds: Long,
) : PaymentProvider {

    private val log = LoggerFactory.getLogger(StubPaymentProvider::class.java)

    override val id = "stub"

    /** Принятые дочерние списания: InvId → момент приёма (материнские подтверждаются ссылкой). */
    private val acceptedCharges = ConcurrentHashMap<Long, Instant>()

    init {
        log.warn("STUB payment provider is active: no real money moves, /api/billing/stub/pay confirms checkouts")
    }

    override fun createCheckout(request: CheckoutRequest): CheckoutUrl {
        log.info("STUB checkout: invId={} amountKopecks={} clubId={}", request.invId, request.amountKopecks, request.clubId)
        return CheckoutUrl("$webAppBaseUrl/api/billing/stub/pay?invId=${request.invId}")
    }

    override fun charge(request: RecurringChargeRequest): ChargeAccepted {
        acceptedCharges[request.invId] = Instant.now()
        log.info("STUB recurring charge accepted: invId={} previousInvId={}", request.invId, request.previousInvId)
        return ChargeAccepted(accepted = true)
    }

    override fun trustsResultSource(clientIp: String): Boolean = true

    override fun parseResultNotification(params: Map<String, String>): ResultNotification =
        ResultNotification(
            invId = params.getValue("InvId").toLong(),
            amountKopecks = params.getValue("OutSum").toInt(),
            paymentMethod = params["PaymentMethod"],
            fee = null,
        )

    override fun queryState(invId: Long): PaymentStateResult {
        val acceptedAt = acceptedCharges[invId] ?: return PaymentStateResult(PaymentState.PENDING)
        val settled = Duration.between(acceptedAt, Instant.now()).seconds >= settleSeconds
        return if (settled) PaymentStateResult(PaymentState.SUCCEEDED, paymentMethod = "BankCard") else PaymentStateResult(PaymentState.PENDING)
    }
}
