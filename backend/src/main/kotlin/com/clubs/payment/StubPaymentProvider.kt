package com.clubs.payment

import com.clubs.common.exception.ForbiddenException
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
// Без matchIfMissing: пустое или незнакомое значение billing.provider НЕ включает стаб молча —
// бина провайдера не будет вовсе, и приложение упадёт на старте, а не начнёт раздавать подписки
// бесплатно (ревью 2026-09-07).
@Component
@ConditionalOnProperty(name = ["billing.provider"], havingValue = "stub")
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

    /**
     * Стаб не имеет подписи, которой можно доверять, поэтому публичный ResultURL для него закрыт:
     * счёт подтверждается только своей страницей `/api/billing/stub/pay`. Иначе (ревью 2026-09-07)
     * любой запрос с угаданным InvId выдавал бы подписку без денег.
     */
    override fun trustsResultSource(clientIp: String): Boolean = false

    override fun parseResultNotification(params: Map<String, String>): ResultNotification =
        throw ForbiddenException("Stub provider does not accept ResultURL notifications")

    override fun queryState(invId: Long): PaymentStateResult {
        val acceptedAt = acceptedCharges[invId] ?: return PaymentStateResult(PaymentState.PENDING)
        val settled = Duration.between(acceptedAt, Instant.now()).seconds >= settleSeconds
        return if (settled) PaymentStateResult(PaymentState.SUCCEEDED, paymentMethod = "BankCard") else PaymentStateResult(PaymentState.PENDING)
    }
}
