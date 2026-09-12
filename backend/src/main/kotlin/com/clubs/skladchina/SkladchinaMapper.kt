package com.clubs.skladchina

import com.clubs.debt.DebtDto
import com.clubs.debt.DebtTotals
import com.clubs.event.Event
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.generated.jooq.tables.records.SkladchinasRecord
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SkladchinaMapper {

    fun toDomain(record: SkladchinasRecord): Skladchina = Skladchina(
        id = record.id!!,
        clubId = record.clubId,
        creatorId = record.creatorId,
        title = record.title,
        description = record.description,
        rules = record.rules,
        photoUrl = record.photoUrl,
        kind = record.kind,
        amountKopecks = record.amountKopecks,
        paymentLink = record.paymentLink,
        paymentMethodNote = record.paymentMethodNote,
        eventId = record.eventId,
        deadline = record.deadline,
        enrollmentUntil = record.enrollmentUntil,
        minParticipants = record.minParticipants,
        lockedAt = record.lockedAt,
        orderedAt = record.orderedAt,
        hiddenFromUserId = record.hiddenFromUserId,
        status = record.status!!,
        closedAt = record.closedAt,
        reminderSentAt = record.reminderSentAt,
        orderRemindedAt = record.orderRemindedAt,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    fun toDetailDto(
        skladchina: Skladchina,
        clubName: String,
        clubAvatarUrl: String?,
        creatorName: String,
        callerId: UUID,
        canCancel: Boolean,
        totals: DebtTotals,
        enrolledCount: Int,
        myEnrolled: Boolean,
        debts: List<DebtDto>,
        event: Event?
    ): SkladchinaDetailDto {
        val isCreator = skladchina.creatorId == callerId
        return SkladchinaDetailDto(
            id = skladchina.id,
            clubId = skladchina.clubId,
            clubName = clubName,
            clubAvatarUrl = clubAvatarUrl,
            creatorId = skladchina.creatorId,
            creatorName = creatorName,
            title = skladchina.title,
            description = skladchina.description,
            rules = skladchina.rules,
            photoUrl = skladchina.photoUrl,
            kind = skladchina.kind.literal,
            amountKopecks = skladchina.amountKopecks,
            targetKopecks = totals.targetKopecks ?: skladchina.amountKopecks,
            receivedKopecks = totals.receivedKopecks,
            claimedKopecks = totals.claimedKopecks,
            paymentLink = skladchina.paymentLink,
            paymentMethodNote = skladchina.paymentMethodNote,
            deadline = skladchina.deadline,
            enrollmentUntil = skladchina.enrollmentUntil,
            minParticipants = skladchina.minParticipants,
            lockedAt = skladchina.lockedAt,
            orderedAt = skladchina.orderedAt,
            eventId = skladchina.eventId,
            eventTitle = event?.title,
            eventDatetime = event?.eventDatetime,
            status = skladchina.status.literal,
            closedAt = skladchina.closedAt,
            isCreator = isCreator,
            canCancel = canCancel,
            isEnrolling = skladchina.isEnrolling,
            enrolledCount = enrolledCount,
            myEnrolled = myEnrolled,
            debtCount = totals.debtCount,
            receivedCount = totals.receivedCount,
            openCount = totals.openCount,
            claimedCount = totals.claimedCount,
            // Своя доля создателя (received с первой секунды) — не «мой долг», а строка в его списке.
            myDebt = debts.firstOrNull { it.debtor.id == callerId && it.creditor.id != callerId },
            debts = if (isCreator) debts else null
        )
    }

    fun toMyFeedItemDto(item: MySkladchinaFeedItem, callerId: UUID): MySkladchinaListItemDto {
        val s = item.skladchina
        val myOpen = item.myDebtStatus?.let { DebtStatus.valueOf(it) in setOf(DebtStatus.waiting, DebtStatus.promised) } ?: false
        return MySkladchinaListItemDto(
            id = s.id,
            title = s.title,
            clubId = s.clubId,
            clubName = item.clubName,
            clubAvatarUrl = item.clubAvatarUrl,
            kind = s.kind.literal,
            amountKopecks = s.amountKopecks,
            targetKopecks = item.totals.targetKopecks ?: s.amountKopecks,
            receivedKopecks = item.totals.receivedKopecks,
            debtCount = item.totals.debtCount,
            receivedCount = item.totals.receivedCount,
            deadline = s.deadline,
            status = s.status.literal,
            isCreator = s.creatorId == callerId,
            myDebtStatus = item.myDebtStatus,
            actionRequired = s.isActive && (myOpen || item.awaitingMyConfirmation),
            photoUrl = s.photoUrl
        )
    }
}
