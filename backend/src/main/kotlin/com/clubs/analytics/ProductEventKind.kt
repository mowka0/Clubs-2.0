package com.clubs.analytics

/** Откуда пришло событие — значение колонки product_event.source. */
enum class EventSource(val literal: String) {
    /** Mini App через POST /api/track: то, чего сервер сам не видит (экраны, запуски, шторки). */
    APP("app"),
    /** Апдейт Telegram-бота: /start, привязка чата, inline-кнопки. */
    BOT("bot"),
    /** Бизнес-логика бэкенда: сервисы, биллинг, шедулеры. */
    SERVER("server"),
}

/**
 * Виды продуктовых событий (docs/modules/product-analytics.md § 3). Литерал — значение
 * product_event.kind, [source] — откуда событие приходит по умолчанию (у `debt_claimed` источник
 * задаётся вызывающим: то же действие бывает из REST и из кнопки бота).
 */
enum class ProductEventKind(val kind: String, val source: EventSource) {
    // Привлечение (funnel.md § 3.1)
    /** «/start» в личке бота — вход с рекламы (campaign из payload) или органика. */
    BOT_STARTED("bot_started", EventSource.BOT),
    /** Чат привязан к клубу (любой из входов привязки). */
    CHAT_CONNECTED("chat_connected", EventSource.BOT),
    /** Клуб потерял чат: отвязка или бота выгнали. */
    CHAT_DISCONNECTED("chat_disconnected", EventSource.BOT),

    // Биллинг (platform-billing.md § 10.10)
    /** Первая встреча чата — с неё пошёл бесплатный период (V99). */
    TRIAL_STARTED("trial_started", EventSource.SERVER),
    PAYWALL_SEEN("paywall_seen", EventSource.SERVER),
    CHECKOUT_STARTED("checkout_started", EventSource.SERVER),
    PAYMENT_SUCCEEDED("payment_succeeded", EventSource.SERVER),
    SUBSCRIPTION_ENDED("subscription_ended", EventSource.SERVER),

    // Действия на сервере (product-analytics.md § 3.3)
    /** Вступил в клуб: props.via = open | invite | application. */
    CLUB_JOINED("club_joined", EventSource.SERVER),
    /** Встреча создана: props.format, props.eventId. */
    EVENT_CREATED("event_created", EventSource.SERVER),
    /** Голос по встрече, включая смену голоса: props.vote, props.eventId. */
    VOTE_CAST("vote_cast", EventSource.SERVER),
    /** Сбор создан: props.kind, props.skladchinaId. */
    SKLADCHINA_CREATED("skladchina_created", EventSource.SERVER),
    /** Действие по долгу: props.action = promise | claim | confirm | reject | forgive, props.debtId. */
    DEBT_CLAIMED("debt_claimed", EventSource.SERVER),
    /** Сообщение в поддержку доставлено (без текста). */
    FEEDBACK_SENT("feedback_sent", EventSource.SERVER),

    // Бот
    /** Нажата inline-кнопка бота: props.button — префикс callback_data без идентификатора. */
    BOT_BUTTON_CLICKED("bot_button_clicked", EventSource.BOT),

    // Mini App (product-analytics.md § 3.2) — принимаются через POST /api/track, см. [AppEventKind]
    APP_OPENED("app_opened", EventSource.APP),
    SCREEN_OPENED("screen_opened", EventSource.APP),
    ONBOARDING_STEP("onboarding_step", EventSource.APP),
    ONBOARDING_COMPLETED("onboarding_completed", EventSource.APP),
    PAY_SHEET_OPENED("pay_sheet_opened", EventSource.APP),
}

/**
 * Виды, которые Mini App вправе прислать в POST /api/track, и допустимые ключи props у каждого.
 * Закрытый список: серверные виды (payment_succeeded и прочие) клиенту недоступны по построению.
 */
enum class AppEventKind(val event: ProductEventKind, val allowedProps: Set<String>) {
    APP_OPENED(ProductEventKind.APP_OPENED, setOf("entry", "platform")),
    SCREEN_OPENED(ProductEventKind.SCREEN_OPENED, setOf("screen", "eventId", "skladchinaId")),
    ONBOARDING_STEP(ProductEventKind.ONBOARDING_STEP, setOf("step")),
    ONBOARDING_COMPLETED(ProductEventKind.ONBOARDING_COMPLETED, emptySet()),
    PAY_SHEET_OPENED(ProductEventKind.PAY_SHEET_OPENED, setOf("reason", "mode"));

    companion object {
        private val byKind = entries.associateBy { it.event.kind }

        /** Вид по литералу из запроса или null, если клиенту такой вид не положен. */
        fun fromKind(kind: String): AppEventKind? = byKind[kind]
    }
}
