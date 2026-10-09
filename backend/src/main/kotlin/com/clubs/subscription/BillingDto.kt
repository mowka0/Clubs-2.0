package com.clubs.subscription

import java.time.OffsetDateTime
import java.util.UUID

/** Состояние биллинга клуба для полоски статуса и шита (platform-billing.md § 6.6). */
enum class BillingState {
    /** Клуб без чата — бесплатен, полоски нет. */
    NO_CHAT,
    /**
     * Чат привязан, но бота из него выгнали: подписка на паузе — стены, напоминаний и списаний
     * нет, период при этом идёт по календарю. Вернули бота — всё оживает (PO 2026-09-16).
     */
    BOT_REMOVED,
    /** Чат подключён, встреч ещё не создавали — бесплатный период не начат (V99). */
    TRIAL_NOT_STARTED,
    /** Бесплатный период идёт: `trialUntil` заполнен. */
    TRIAL,
    /** Бесплатный период кончился, подписки ещё не было. */
    TRIAL_ENDED,
    /**
     * Оплаченный период идёт — или кончился, но автосписание ещё впереди либо ждёт ответа
     * провайдера (`ServiceSubscription.awaitsAutoRenewal`): платить вручную не нужно.
     */
    ACTIVE,
    /**
     * Период кончился, грейс идёт — всё работает, ждём оплату. Сюда же — неудачное автосписание
     * утром дня окончания, ещё до часа окончания: владельцу нужна кнопка «Продлить», как в DM.
     */
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
    /**
     * Провайдер сейчас умеет сохранять карту (рекуррент разрешён магазину, `PaymentProvider.recurringAvailable`).
     * false → ползунок недоступен ещё до первой оплаты: обещать «спишем с этой же карты» нельзя, карта не сохранится.
     */
    val autopayAvailable: Boolean,
    /** Есть свежий неоплаченный материнский счёт — фронт показывает «проверяем оплату». */
    val pendingCheckout: Boolean,
    /** ФИО самозанятого-получателя целиком — показывается в шите и в оферте (PO 2026-09-07). Пусто = не настроено. */
    val recipientName: String,
    /**
     * Смотрящий — владелец: только ему чекбокс согласия на автосписание и ползунок. Любой другой
     * участник платит разово, карта не сохраняется (billing-member-pays.md M2).
     */
    val canEnableAutopay: Boolean,
    /**
     * Пора платить — плашка возвращается на главную страницу клуба ко всем участникам: до конца
     * бесплатного или оплаченного периода 7 календарных дней МСК или меньше, грейс, не оплачено (M4).
     */
    val paymentDue: Boolean,
    /** Крайний оплативший, пока подписка жива (ACTIVE/GRACE): имя на плашке и 💛 в списке участников (M6). */
    val lastPayer: BillingPayerDto?,
)

/** Кто оплатил клуб последним; [name] — как в списке участников. */
data class BillingPayerDto(val userId: UUID, val name: String)

/**
 * Отметка «Я согласен на автоматические списания…» на момент чекаута; переносится на подписку при
 * оплате. По умолчанию снята — требование Robokassa к форме (2026-10-05), без тела запроса согласия нет.
 */
data class StartCheckoutRequest(val autopay: Boolean = false)

data class CheckoutDto(val paymentUrl: String, val invId: Long)

data class AutopayRequest(val autopay: Boolean)

/** Ответ служебного «списать сейчас»: номер отправленного провайдеру счёта — искать его в логах и в кабинете. */
data class ManualChargeDto(val invId: Long)
