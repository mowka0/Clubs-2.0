package com.clubs.subscription

import java.time.OffsetDateTime

/** Состояние биллинга клуба для полоски статуса и шита (platform-billing.md § 6.6). */
enum class BillingState {
    /** Клуб без чата — бесплатен, полоски нет. */
    NO_CHAT,
    /** Чат подключён, бесплатная встреча ещё не взята. */
    FREE_MEETING_AVAILABLE,
    /** Бесплатная встреча использована, подписки ещё не было. */
    FREE_MEETING_USED,
    /** Оплаченный период идёт. */
    ACTIVE,
    /** Период кончился, грейс идёт — всё работает, ждём оплату. */
    GRACE,
    /** Грейс исчерпан — новые встречи только после оплаты. */
    ENDED,
}

data class BillingStatusDto(
    val state: BillingState,
    val priceKopecks: Int,
    val currentPeriodEnd: OffsetDateTime?,
    val graceUntil: OffsetDateTime?,
    val autopay: Boolean,
    val autopayPossible: Boolean,
    /** Есть свежий неоплаченный материнский счёт — фронт показывает «проверяем оплату». */
    val pendingCheckout: Boolean,
    /** ФИО самозанятого-получателя целиком — показывается в шите и в оферте (PO 2026-09-07). Пусто = не настроено. */
    val recipientName: String,
    /**
     * Смотрящий может оплачивать: платит только владелец клуба (R1), а статус видит и
     * со-организатор с MANAGE_EVENTS — ему шит показывает «попросите владельца» вместо кнопки,
     * иначе чекаут отвечал бы ему 403.
     */
    val canPay: Boolean,
)

/** Ползунок «Продлевать автоматически» на момент чекаута; переносится на подписку при оплате. */
data class StartCheckoutRequest(val autopay: Boolean = true)

data class CheckoutDto(val paymentUrl: String, val invId: Long)

data class AutopayRequest(val autopay: Boolean)
