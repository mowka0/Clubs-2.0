package com.clubs.debt

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Доменные события долга — DM после коммита (DebtBotNotifier). Несут уже прочитанный контекст,
 * чтобы слушатель не ходил в БД за названием сбора и именами.
 */

/** Долг создан вне создания сбора: «добавить человека» или новый долг у замены. */
data class DebtCreatedEvent(
    val debt: DebtWithContext
)

/** Должник нажал «Отдал» — получателю DM с кнопками «Получил / Не получил». */
data class DebtClaimedEvent(
    val debt: DebtWithContext
)

/** Получатель нажал «Не получил» — должнику DM «приложите чек». */
data class DebtRejectedEvent(
    val debt: DebtWithContext,
    // per_head после заказа: «Не получил» = выбыл, DM другой.
    val dropped: Boolean
)

/** Получатель изменил сумму shared-долга. */
data class DebtAmountChangedEvent(
    val debt: DebtWithContext,
    val oldAmountKopecks: Long
)

/** Создатель заменил должника: прежнему долг снят, у [replacement] новый долг той же суммы. */
data class DebtReplacedEvent(
    val replacedUserId: UUID,
    val replacement: DebtWithContext
)

/** Плательщик по сальдо нажал «Отдал Σ» — получателю один DM с кнопками. */
data class SettlementClaimedEvent(
    val settlement: DebtSettlement,
    val payerName: String,
    val debtCount: Int,
    val claimedAt: OffsetDateTime
)
