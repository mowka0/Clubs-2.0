package com.clubs.payment

data class PaymentConfirmedEvent(
    val telegramId: Long,
    val clubName: String
)
