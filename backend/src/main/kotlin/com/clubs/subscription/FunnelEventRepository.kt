package com.clubs.subscription

import java.util.UUID

/** Шаги воронки, которые пишет биллинг (platform-billing.md § 10.10). Литерал — значение funnel_event.kind. */
enum class FunnelStep(val kind: String) {
    FREE_MEETING_USED("free_meeting_used"),
    PAYWALL_SEEN("paywall_seen"),
    CHECKOUT_STARTED("checkout_started"),
    PAYMENT_SUCCEEDED("payment_succeeded"),
    SUBSCRIPTION_ENDED("subscription_ended"),
}

/** Факты воронки (таблица funnel_event): только запись, в логику продукта не входят. */
interface FunnelEventRepository {

    /** Пишет шаг в текущей транзакции — откатывается вместе с ней. */
    fun record(step: FunnelStep, userId: UUID?, clubId: UUID?, campaign: String? = null)

    /** Пишет шаг в отдельной транзакции — переживает откат вызывающей (пейволл откатывает вставку встречи). */
    fun recordDetached(step: FunnelStep, userId: UUID?, clubId: UUID?)
}
