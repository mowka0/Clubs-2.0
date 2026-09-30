package com.clubs.subscription

import java.util.UUID

/** Шаги воронки (funnel.md § 3.1, platform-billing.md § 10.10). Литерал — значение funnel_event.kind. */
enum class FunnelStep(val kind: String) {
    /** «/start» в личке бота — вход с рекламы (campaign из payload) или органика. */
    BOT_STARTED("bot_started"),
    /** Чат привязан к клубу (любой из входов привязки). */
    CHAT_CONNECTED("chat_connected"),
    /** Клуб потерял чат: отвязка или бота выгнали. */
    CHAT_DISCONNECTED("chat_disconnected"),
    /** Первая встреча чата — с неё пошёл бесплатный период (V99). */
    TRIAL_STARTED("trial_started"),
    PAYWALL_SEEN("paywall_seen"),
    CHECKOUT_STARTED("checkout_started"),
    PAYMENT_SUCCEEDED("payment_succeeded"),
    SUBSCRIPTION_ENDED("subscription_ended"),
}

/** Факты воронки (таблица funnel_event): только запись, в логику продукта не входят. */
interface FunnelEventRepository {

    /**
     * Пишет шаг в текущей транзакции — откатывается вместе с ней. [telegramId] — у шагов привлечения,
     * где пользователя в users может ещё не быть (V100); [campaign] — только у bot_started.
     */
    fun record(step: FunnelStep, userId: UUID?, clubId: UUID?, campaign: String? = null, telegramId: Long? = null)

    /** Пишет шаг в отдельной транзакции — переживает откат вызывающей (пейволл откатывает вставку встречи). */
    fun recordDetached(step: FunnelStep, userId: UUID?, clubId: UUID?)
}
