package com.clubs.analytics

import java.util.UUID

/** Одно событие для пакетной записи (пачка от Mini App). */
data class NewProductEvent(
    val kind: ProductEventKind,
    val userId: UUID?,
    val clubId: UUID?,
    val props: Map<String, Any?>? = null,
    val source: EventSource = kind.source,
)

/** Журнал продуктовых событий (таблица product_event): только запись, в логику продукта не входит. */
interface ProductEventRepository {

    /**
     * Пишет событие в текущей транзакции — откатывается вместе с ней. [campaign] — только у
     * bot_started; [telegramId] — у шагов привлечения и кнопок бота, где пользователя в users
     * может ещё не быть; [props] — детали по product-analytics.md § 3; [source] переопределяется,
     * когда одно действие приходит разными путями (долг из REST и из кнопки бота).
     */
    fun record(
        kind: ProductEventKind,
        userId: UUID?,
        clubId: UUID?,
        campaign: String? = null,
        telegramId: Long? = null,
        props: Map<String, Any?>? = null,
        source: EventSource = kind.source,
    )

    /** Пишет событие в отдельной транзакции — переживает откат вызывающей (пейволл откатывает вставку встречи). */
    fun recordDetached(kind: ProductEventKind, userId: UUID?, clubId: UUID?)

    /** Пачка одним insert'ом в текущей транзакции. */
    fun recordAll(events: List<NewProductEvent>)
}
