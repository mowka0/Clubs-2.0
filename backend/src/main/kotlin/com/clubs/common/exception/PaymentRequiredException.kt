package com.clubs.common.exception

/**
 * Thrown when an action requires a higher capacity plan than the organizer currently holds
 * (e.g. creating a 2nd+ paid club on FREE). Mapped to HTTP 402 with the paywall payload so the
 * frontend can render the upgrade modal. Plans are passed as literals to keep this layer free of
 * generated-enum coupling.
 */
class PaymentRequiredException(
    val currentPlan: String,
    val requiredPlan: String,
    val priceKopecks: Int,
    message: String = "A subscription is required to create another paid club",
) : RuntimeException(message)
