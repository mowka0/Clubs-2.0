package com.clubs.subscription

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * Страница «оплаты» стаб-провайдера: ссылка чекаута ведёт сюда, счёт подтверждается как по
 * ResultURL, человек уходит на `/pay/return`. Существует только при `billing.provider=stub`
 * (dev/staging) — на проде с Robokassa бина нет, маршрут отвечает 404.
 *
 * Без параметров показывает выбор исхода: карта (автопродление доступно), СБП (карта не
 * сохраняется — автопродление недоступно) и отказ. Иначе staging-прогон не мог бы проверить
 * сценарии СБП и неоплаты: у настоящей Robokassa этот выбор делает её собственная страница.
 */
// Без matchIfMissing — см. StubPaymentProvider: неизвестное значение не должно молча открывать
// бесплатную оплату.
@RestController
@ConditionalOnProperty(name = ["billing.provider"], havingValue = "stub")
class StubCheckoutController(
    private val billingService: BillingService,
    @Value("\${billing.success-url}") private val successUrl: String,
    @Value("\${billing.fail-url}") private val failUrl: String,
) {

    @GetMapping("/api/billing/stub/pay")
    fun pay(
        @RequestParam invId: Long,
        // BankCard | SBP — способ, которым «заплатили»; от него зависит autopay_possible.
        @RequestParam(required = false) method: String?,
        // fail — «отказался от оплаты»: счёт остаётся неоплаченным, человек уходит на /pay/fail.
        @RequestParam(required = false) outcome: String?,
    ): ResponseEntity<String> {
        // Без `club`: страница неудачи и так ведёт в бота, а искать клуб по неоплаченному
        // счёту ради одной кнопки staging-заглушки незачем.
        if (outcome == "fail") return redirect(failUrl)
        if (method == null) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(choicePage(invId))
        }
        val clubId = billingService.settleStubPayment(invId, method) ?: return ResponseEntity.notFound().build()
        return redirect("$successUrl?club=$clubId")
    }

    private fun redirect(location: String): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()

    /** Минимальная страница выбора: это staging-инструмент, а не продуктовый экран. */
    private fun choicePage(invId: Long): String = """
        <!doctype html><html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Тестовая оплата</title>
        <style>
          body { font-family: -apple-system, system-ui, sans-serif; margin: 0; padding: 32px 20px;
                 background: #f5f5f7; color: #111; }
          .card { max-width: 420px; margin: 0 auto; background: #fff; border-radius: 16px; padding: 22px; }
          h1 { font-size: 19px; margin: 0 0 6px; }
          p { font-size: 13px; color: #666; margin: 0 0 18px; line-height: 1.45; }
          a { display: block; text-align: center; text-decoration: none; border-radius: 12px;
              padding: 13px; font-size: 15px; font-weight: 600; margin-bottom: 10px; }
          .card-pay { background: #ff7b4a; color: #fff; }
          .sbp { background: #eef0f4; color: #111; }
          .fail { background: transparent; color: #999; font-weight: 500; }
        </style></head><body><div class="card">
        <h1>Тестовая оплата, счёт №$invId</h1>
        <p>Провайдер подменён заглушкой — деньги не двигаются. Выберите, чем «платим»:
        от способа зависит, будет ли доступно автопродление.</p>
        <a class="card-pay" href="/api/billing/stub/pay?invId=$invId&method=BankCard">Оплатить картой</a>
        <a class="sbp" href="/api/billing/stub/pay?invId=$invId&method=SBP">Оплатить по СБП</a>
        <a class="fail" href="/api/billing/stub/pay?invId=$invId&outcome=fail">Отказаться от оплаты</a>
        </div></body></html>
    """.trimIndent()
}
