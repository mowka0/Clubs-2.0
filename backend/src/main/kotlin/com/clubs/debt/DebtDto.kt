package com.clubs.debt

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class DebtPersonDto(
    val id: UUID,
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val avatarUrl: String?
)

/** Строка долга: одна и та же на экране сбора, в паре и у создателя в списке. */
data class DebtDto(
    val id: UUID,
    val skladchinaId: UUID,
    val skladchinaTitle: String,
    val skladchinaKind: String,
    val clubId: UUID,
    val clubName: String,
    val debtor: DebtPersonDto,
    val creditor: DebtPersonDto,
    val amountKopecks: Long,
    val dueAt: OffsetDateTime?,
    val status: String,
    val promisedAt: LocalDate?,
    val claimedAt: OffsetDateTime?,
    val confirmedAt: OffsetDateTime?,
    val rejectedAt: OffsetDateTime?,
    val rejectNote: String?,
    val note: String?,
    val receiptUrl: String?,
    // Долг в составе сальдо пары: одиночные кнопки скрыты, пока сальдо не разобрано.
    val settlementId: UUID?,
    val paymentLink: String,
    val paymentMethodNote: String?,
    // Открытый долг с прошедшим сроком — строка и пост показывают «срок вышел».
    val isOverdue: Boolean,
    val createdAt: OffsetDateTime
)

/** Плашка человека на экране «Долги»: сальдо пары и что от меня ждут. */
data class DebtCounterpartyDto(
    val user: DebtPersonDto,
    // Положительное = мне должны, отрицательное = я должен.
    val balanceKopecks: Long,
    val oweKopecks: Long,
    val owedKopecks: Long,
    val debtCount: Int,
    val nearestDueAt: OffsetDateTime?,
    // «Говорит, что отдал · подтвердите»: число claimed-долгов и сальдо, где отвечать мне.
    val awaitingMyConfirmation: Int,
    val awaitingTheirConfirmation: Int
)

/** GET /api/debts: две цифры для плитки профиля и люди для экрана «Долги». */
data class DebtsOverviewDto(
    val oweKopecks: Long,
    val owedKopecks: Long,
    val awaitingMyConfirmation: Int,
    val people: List<DebtCounterpartyDto>
)

data class DebtSettlementDto(
    val id: UUID,
    val payerId: UUID,
    val payeeId: UUID,
    val amountKopecks: Long,
    val status: String,
    val claimedAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?
)

/** GET /api/debts/with/{userId}: пара в обе стороны, сальдо и активное подтверждение сальдо. */
data class DebtPairDto(
    val user: DebtPersonDto,
    val owe: List<DebtDto>,
    val owed: List<DebtDto>,
    val oweKopecks: Long,
    val owedKopecks: Long,
    val balanceKopecks: Long,
    val settlement: DebtSettlementDto?
)

data class PromiseRequest(
    @field:NotNull
    val date: LocalDate
)

data class RejectDebtRequest(
    @field:Size(max = 500)
    val note: String? = null
)

data class ChangeDebtAmountRequest(
    @field:NotNull @field:Positive
    val amountKopecks: Long
)

data class DebtReceiptRequest(
    @field:NotBlank @field:Size(max = 500)
    val url: String
)

data class DebtNoteRequest(
    @field:NotBlank @field:Size(max = 500)
    val note: String
)
