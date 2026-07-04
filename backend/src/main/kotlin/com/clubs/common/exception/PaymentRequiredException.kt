package com.clubs.common.exception

/**
 * Бросается, когда действие требует более высокого плана ёмкости, чем сейчас у организатора
 * (например, создание 2-го+ платного клуба на FREE). Маппится в HTTP 402 с payload пейволла,
 * чтобы фронтенд мог отрендерить модалку апгрейда. Планы передаются как литералы, чтобы этот
 * слой не был связан с generated-enum.
 */
class PaymentRequiredException(
    val currentPlan: String,
    val requiredPlan: String,
    val priceKopecks: Int,
    message: String = "A subscription is required to create another paid club",
) : RuntimeException(message)
