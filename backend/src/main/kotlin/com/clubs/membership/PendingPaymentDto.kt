package com.clubs.membership

import java.util.UUID

data class PendingPaymentDto(
    val status: String = "pending_payment",
    val clubId: UUID,
    val priceStars: Int,
    val message: String = "Оплатите подписку через бота. Счёт отправлен в Telegram."
)
