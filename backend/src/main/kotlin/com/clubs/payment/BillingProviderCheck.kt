package com.clubs.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Проверка настройки провайдера платежей на старте. Оба [PaymentProvider] объявлены через
 * `@ConditionalOnProperty` без `matchIfMissing` намеренно (пустое значение не должно молча
 * включать стаб — см. [StubPaymentProvider]), но без неё Spring падает невнятным
 * «no qualifying bean of type PaymentProvider», и причина ищется по логам.
 * Идёт первой благодаря `@DependsOn` на `BillingService` и прямо называет переменную.
 */
@Component
class BillingProviderCheck(@Value("\${billing.provider:}") provider: String) {

    init {
        require(provider in SUPPORTED) {
            "BILLING_PROVIDER не задан или неизвестен (получено: '$provider'). " +
                "Ожидается stub (dev и staging без ключей) или robokassa (боевые деньги). " +
                "На staging/проде переменная задаётся в Coolify."
        }
    }

    companion object {
        // Значения billing.provider, у которых есть реализация PaymentProvider.
        private val SUPPORTED = setOf("stub", "robokassa")
    }
}
