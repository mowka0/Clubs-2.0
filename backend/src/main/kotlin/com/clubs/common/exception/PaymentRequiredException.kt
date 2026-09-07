package com.clubs.common.exception

import java.util.UUID

/** Причина пейволла — фронт по ней выбирает текст шита оплаты за чат. */
enum class PaywallReason {
    /** Бесплатная встреча чата уже использована, подписки не было. */
    FREE_MEETING_USED,
    /** Подписка была, но период и грейс после него истекли. */
    SUBSCRIPTION_EXPIRED,
}

/**
 * Бросается гейтом биллинга, когда создание встречи требует оплаты подписки за чат
 * (docs/modules/platform-billing.md § 6.4). Маппится в HTTP 402 с payload пейволла, чтобы
 * фронтенд открыл шит оплаты поверх формы. Цена считается на сервере из subscription_pricing —
 * клиент её не передаёт.
 */
class PaymentRequiredException(
    val reason: PaywallReason,
    val clubId: UUID,
    val priceKopecks: Int,
    message: String = "A chat subscription is required to create another meeting",
) : RuntimeException(message)
