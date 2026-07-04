package com.clubs.payment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * No-op провайдер для staging/dev: "активирует" синхронно и НЕ двигает реальные деньги. За ним
 * движок подписок работает целиком (subscribe → машина состояний → capacity → cancel). Подключение
 * реального эквайера = второй @Component (пометить @Primary или включить через @ConditionalOnProperty) —
 * движок менять не нужно.
 */
@Component
class StubPaymentProvider(
    // Длительность одного периода подписки в днях (для расчёта currentPeriodEnd).
    @Value("\${subscription.period-days:30}") private val periodDays: Long,
) : PaymentProvider {

    private val log = LoggerFactory.getLogger(StubPaymentProvider::class.java)

    override fun createSubscription(command: CreateSubscriptionCommand): ProviderSubscription {
        log.info(
            "STUB createSubscription: payer={} role={} plan={} priceKopecks={} club={}",
            command.payerUserId, command.payerRole, command.plan, command.priceKopecks, command.subjectClubId,
        )
        return ProviderSubscription(
            providerToken = "stub-${UUID.randomUUID()}",
            currentPeriodEnd = OffsetDateTime.now().plusDays(periodDays),
        )
    }

    override fun cancelSubscription(providerToken: String?) {
        log.info("STUB cancelSubscription: token={}", providerToken)
    }

    override fun parseWebhook(rawBody: String, signature: String?): WebhookResult =
        WebhookResult.Ignored("stub provider receives no real webhooks")
}
