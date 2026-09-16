package com.clubs.subscription

import java.time.OffsetDateTime

/** Состояние биллинга клуба для полоски статуса и шита (platform-billing.md § 6.6). */
enum class BillingState {
    /** Клуб без чата — бесплатен, полоски нет. */
    NO_CHAT,
    /** Чат подключён, встреч ещё не создавали — бесплатный период не начат (V99). */
    TRIAL_NOT_STARTED,
    /** Бесплатный период идёт: `trialUntil` заполнен. */
    TRIAL,
    /** Бесплатный период кончился, подписки ещё не было. */
    TRIAL_ENDED,
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
    /** До какого момента чат живёт бесплатно; null — период ещё не начат или уже неважен. */
    val trialUntil: OffsetDateTime?,
    /** Длина бесплатного периода в днях (billing.trial-days) — чтобы тексты не зашивали число. */
    val trialDays: Int,
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

/** Ответ служебного «списать сейчас»: номер отправленного провайдеру счёта — искать его в логах и в кабинете. */
data class ManualChargeDto(val invId: Long)
