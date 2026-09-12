package com.clubs.debt

import com.clubs.generated.jooq.enums.DebtSettlementStatus
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.enums.SkladchinaKind
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Открытые состояния долга: по ним считаются сальдо, закрытие сбора и напоминания. */
val OPEN_DEBT_STATUSES: Set<DebtStatus> = setOf(DebtStatus.waiting, DebtStatus.promised, DebtStatus.claimed)

/** «Живые» долги — те, что входят в знаменатель «получено X из Y»: открытые плюс полученные. */
val LIVE_DEBT_STATUSES: Set<DebtStatus> = OPEN_DEBT_STATUSES + DebtStatus.received

/**
 * Долг: кто → кому · сколько · за что (сбор) · до какого · состояние (docs/modules/skladchina-v3.md § 2.2).
 * Подтверждает получатель. Один долг на пару (сбор, должник).
 */
data class Debt(
    val id: UUID,
    val skladchinaId: UUID,
    val debtorId: UUID,
    val creditorId: UUID,
    val amountKopecks: Long,
    val dueAt: OffsetDateTime?,
    val status: DebtStatus,
    val promisedAt: LocalDate?,
    val claimedAt: OffsetDateTime?,
    val confirmedAt: OffsetDateTime?,
    val rejectedAt: OffsetDateTime?,
    val rejectNote: String?,
    val note: String?,
    val receiptUrl: String?,
    val settlementId: UUID?,
    val reputationPlusAt: OffsetDateTime?,
    val reputationMinusAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
) {
    val isOpen: Boolean get() = status in OPEN_DEBT_STATUSES
}

/** Новый долг для вставки: статус и штампы задаёт создающий сценарий (доля создателя сразу received). */
data class NewDebt(
    val skladchinaId: UUID,
    val debtorId: UUID,
    val creditorId: UUID,
    val amountKopecks: Long,
    val dueAt: OffsetDateTime?,
    val status: DebtStatus = DebtStatus.waiting,
    val claimedAt: OffsetDateTime? = null,
    val confirmedAt: OffsetDateTime? = null,
    val note: String? = null
)

/** Сторона долга для экранов: имя, username, аватар. */
data class DebtPerson(
    val id: UUID,
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val avatarUrl: String?
)

/**
 * Долг с контекстом для экранов и DM: сбор (название, вид, реквизиты, стадия заказа), клуб и обе стороны.
 * Читается одним JOIN, чтобы ни экран «Долги», ни напоминания не ходили в БД по одному долгу.
 */
data class DebtWithContext(
    val debt: Debt,
    val skladchinaTitle: String,
    val skladchinaKind: SkladchinaKind,
    val skladchinaOrderedAt: OffsetDateTime?,
    val clubId: UUID,
    val clubName: String,
    val paymentLink: String,
    val paymentMethodNote: String?,
    val debtor: DebtPerson,
    val creditor: DebtPerson
)

/**
 * Итоги сбора по его долгам. Знаменатель [targetKopecks] — сумма живых долгов (ожидаемых и
 * полученных); прощённые и выбывшие в него не входят. У сбора без долгов = null, и экран
 * подставляет amountKopecks сбора.
 */
data class DebtTotals(
    val receivedKopecks: Long,
    val claimedKopecks: Long,
    val targetKopecks: Long?,
    val debtCount: Int,
    val receivedCount: Int,
    val openCount: Int,
    val claimedCount: Int
) {
    companion object {
        val EMPTY = DebtTotals(0, 0, null, 0, 0, 0, 0)
    }
}

/** Сальдо пары: «Отдал Σ» разом по всем открытым долгам двух людей (§ 2.3). */
data class DebtSettlement(
    val id: UUID,
    val payerId: UUID,
    val payeeId: UUID,
    val amountKopecks: Long,
    val status: DebtSettlementStatus,
    val claimedAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?
)
