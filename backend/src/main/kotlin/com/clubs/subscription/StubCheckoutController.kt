package com.clubs.subscription

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Страница «оплаты» стаб-провайдера: ссылка чекаута ведёт сюда, счёт подтверждается как по
 * ResultURL, человек уходит на `/pay/return`. Существует только при `billing.provider=stub`
 * (dev/staging) — на проде с Robokassa бина нет, маршрут отвечает 404.
 */
@RestController
@ConditionalOnProperty(name = ["billing.provider"], havingValue = "stub", matchIfMissing = true)
class StubCheckoutController(
    private val billingService: BillingService,
    @Value("\${billing.success-url}") private val successUrl: String,
) {

    @GetMapping("/api/billing/stub/pay")
    fun pay(
        @RequestParam invId: Long,
        // «SBP» воспроизводит оплату без сохранённой карты: автопродление недоступно.
        @RequestParam(defaultValue = "BankCard") method: String,
    ): ResponseEntity<Void> {
        val clubId = billingService.settleStubPayment(invId, method) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("$successUrl?club=$clubId")).build()
    }
}
