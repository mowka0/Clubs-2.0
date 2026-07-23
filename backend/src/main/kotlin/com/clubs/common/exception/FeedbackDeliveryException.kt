package com.clubs.common.exception

/** Доставка обратной связи в Telegram не удалась (саппорт-аккаунта нет в users или сбой Bot API) → 503. */
class FeedbackDeliveryException(message: String) : RuntimeException(message)
