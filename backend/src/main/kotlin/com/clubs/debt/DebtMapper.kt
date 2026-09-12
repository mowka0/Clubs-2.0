package com.clubs.debt

import com.clubs.generated.jooq.tables.records.DebtSettlementsRecord
import com.clubs.generated.jooq.tables.records.DebtsRecord
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class DebtMapper {

    fun toDomain(record: DebtsRecord): Debt = Debt(
        id = record.id!!,
        skladchinaId = record.skladchinaId,
        debtorId = record.debtorId,
        creditorId = record.creditorId,
        amountKopecks = record.amountKopecks,
        dueAt = record.dueAt,
        status = record.status!!,
        promisedAt = record.promisedAt,
        claimedAt = record.claimedAt,
        confirmedAt = record.confirmedAt,
        rejectedAt = record.rejectedAt,
        rejectNote = record.rejectNote,
        note = record.note,
        receiptUrl = record.receiptUrl,
        settlementId = record.settlementId,
        reputationPlusAt = record.reputationPlusAt,
        reputationMinusAt = record.reputationMinusAt,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    fun toSettlement(record: DebtSettlementsRecord): DebtSettlement = DebtSettlement(
        id = record.id!!,
        payerId = record.payerId,
        payeeId = record.payeeId,
        amountKopecks = record.amountKopecks,
        status = record.status!!,
        claimedAt = record.claimedAt!!,
        resolvedAt = record.resolvedAt
    )

    fun toPersonDto(p: DebtPerson): DebtPersonDto =
        DebtPersonDto(p.id, p.firstName, p.lastName, p.username, p.avatarUrl)

    fun toDto(item: DebtWithContext, now: OffsetDateTime = OffsetDateTime.now()): DebtDto {
        val d = item.debt
        return DebtDto(
            id = d.id,
            skladchinaId = d.skladchinaId,
            skladchinaTitle = item.skladchinaTitle,
            skladchinaKind = item.skladchinaKind.literal,
            clubId = item.clubId,
            clubName = item.clubName,
            debtor = toPersonDto(item.debtor),
            creditor = toPersonDto(item.creditor),
            amountKopecks = d.amountKopecks,
            dueAt = d.dueAt,
            status = d.status.literal,
            promisedAt = d.promisedAt,
            claimedAt = d.claimedAt,
            confirmedAt = d.confirmedAt,
            rejectedAt = d.rejectedAt,
            rejectNote = d.rejectNote,
            note = d.note,
            receiptUrl = d.receiptUrl,
            settlementId = d.settlementId,
            paymentLink = item.paymentLink,
            paymentMethodNote = item.paymentMethodNote,
            isOverdue = d.isOpen && d.dueAt != null && d.dueAt.isBefore(now),
            createdAt = d.createdAt
        )
    }

    fun toSettlementDto(s: DebtSettlement): DebtSettlementDto = DebtSettlementDto(
        id = s.id,
        payerId = s.payerId,
        payeeId = s.payeeId,
        amountKopecks = s.amountKopecks,
        status = s.status.literal,
        claimedAt = s.claimedAt,
        resolvedAt = s.resolvedAt
    )
}
