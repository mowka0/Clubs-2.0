package com.clubs.skladchina

import com.clubs.event.Event
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.tables.records.SkladchinaParticipantsRecord
import com.clubs.generated.jooq.tables.records.SkladchinasRecord
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
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
        template = record.template!!,
        paymentMode = record.paymentMode,
        totalGoalKopecks = record.totalGoalKopecks,
        paymentLink = record.paymentLink,
        paymentMethodNote = record.paymentMethodNote,
        eventId = record.eventId,
        deadline = record.deadline,
        affectsReputation = record.affectsReputation ?: false,
        status = record.status!!,
        closedAt = record.closedAt,
        closedBy = record.closedBy,
        confirmationRequestedAt = record.confirmationRequestedAt,
        createdAt = record.createdAt!!,
        updatedAt = record.updatedAt!!
    )

    fun toParticipantDomain(record: SkladchinaParticipantsRecord): SkladchinaParticipant = SkladchinaParticipant(
        skladchinaId = record.skladchinaId,
        userId = record.userId,
        expectedAmountKopecks = record.expectedAmountKopecks,
        declaredAmountKopecks = record.declaredAmountKopecks,
        status = record.status!!,
        paidAt = record.paidAt,
        declinedAt = record.declinedAt,
        reputationApplied = record.reputationApplied ?: false,
        declineNote = record.declineNote,
        declineRequestedAt = record.declineRequestedAt,
        declineRejected = record.declineRejected ?: false,
        paymentConfirmedAt = record.paymentConfirmedAt,
        paymentRejectedAt = record.paymentRejectedAt,
        paymentRejectNote = record.paymentRejectNote,
        receiptUrl = record.receiptUrl,
        receiptNote = record.receiptNote,
        disputedAt = record.disputedAt,
        disputeTerminal = record.disputeTerminal ?: false,
        createdAt = record.createdAt!!
    )

    fun toDetailDto(
        skladchina: Skladchina,
        clubName: String,
        clubAvatarUrl: String?,
        callerUserId: UUID,
        // Вызывающий — менеджер клуба сбора (владелец или активный со-орг); вычисляет сервис,
        // маппер репозитории не дёргает.
        callerIsManager: Boolean,
        participants: List<SkladchinaParticipantInfo>,
        collectedKopecks: Long,
        // Встреча-источник счёта; null у сборов без события (custom) — тогда блока встречи нет.
        event: Event? = null
    ): SkladchinaDetailDto {
        // У-7: имя поля isOrganizerView сохранено при расширении семантики (creator ИЛИ manager) —
        // минимизация фронт-диффа; гейтит орг-действия и список участников в DTO.
        val isOrganizerView = skladchina.creatorId == callerUserId || callerIsManager
        val myParticipant = participants.firstOrNull { it.userId == callerUserId }
        val paidCount = participants.count { it.status in PAID_LIKE_STATUSES }
        val confirmedCount = participants.count { it.status == SkladchinaParticipantStatus.payment_confirmed }
        val confirmedKopecks = participants
            .filter { it.status == SkladchinaParticipantStatus.payment_confirmed }
            .sumOf { it.declaredAmountKopecks ?: 0L }
        val pendingCount = participants.count { it.status == SkladchinaParticipantStatus.pending }
        // V89: сбор завершён, но организатор ещё не сверил деньги — ни оплатить, ни отказаться уже
        // нельзя, экран показывает «ждём сверки». Состояние вычисляемое, в БД его нет.
        val awaitingConfirmation = skladchina.status == SkladchinaStatus.active &&
            (pendingCount == 0 || !skladchina.deadline.isAfter(OffsetDateTime.now()))

        return SkladchinaDetailDto(
            id = skladchina.id,
            clubId = skladchina.clubId,
            clubName = clubName,
            clubAvatarUrl = clubAvatarUrl,
            creatorId = skladchina.creatorId,
            title = skladchina.title,
            description = skladchina.description,
            rules = skladchina.rules,
            photoUrl = skladchina.photoUrl,
            template = skladchina.template.literal,
            eventId = skladchina.eventId,
            eventTitle = event?.title,
            eventDatetime = event?.eventDatetime,
            paymentMode = skladchina.paymentMode.literal,
            totalGoalKopecks = skladchina.totalGoalKopecks,
            collectedKopecks = collectedKopecks,
            confirmedKopecks = confirmedKopecks,
            paymentLink = skladchina.paymentLink,
            paymentMethodNote = skladchina.paymentMethodNote,
            deadline = skladchina.deadline,
            affectsReputation = skladchina.affectsReputation,
            status = skladchina.status.literal,
            closedAt = skladchina.closedAt,
            isOrganizerView = isOrganizerView,
            myStatus = myParticipant?.status?.literal,
            myExpectedAmountKopecks = myParticipant?.expectedAmountKopecks,
            myDeclaredAmountKopecks = myParticipant?.declaredAmountKopecks,
            myDeclineRequested = myParticipant?.declineRequestedAt != null,
            myDeclineRejected = myParticipant?.declineRejected ?: false,
            myDeclineRejectNote = myParticipant?.declineRejectNote,
            awaitingConfirmation = awaitingConfirmation,
            myPaymentRejectNote = myParticipant?.paymentRejectNote,
            myReceiptUrl = myParticipant?.receiptUrl,
            myDisputeDeadline = myParticipant?.paymentRejectedAt
                ?.plusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS),
            myDisputeTerminal = myParticipant?.disputeTerminal ?: false,
            participants = if (isOrganizerView) participants.map(::toParticipantDto) else null,
            participantCount = participants.size,
            paidCount = paidCount,
            confirmedCount = confirmedCount,
            pendingCount = pendingCount
        )
    }

    fun toMyFeedItemDto(item: MySkladchinaFeedItem, callerUserId: UUID, callerIsManager: Boolean): MySkladchinaListItemDto {
        // У-7: creator ИЛИ manager (см. toDetailDto).
        val isOrganizerView = item.skladchina.creatorId == callerUserId || callerIsManager
        // Дело на участнике: неоплаченный идущий сбор либо отклонённая оплата, по которой ещё
        // МОЖНО прислать чек (сбор к этому моменту уже закрыт). Окно то же, что у счётчика
        // бейджа, иначе список подсвечивал бы дело, которого уже нет.
        val receiptWindowOpen = item.myStatus == SkladchinaParticipantStatus.payment_rejected &&
            item.myPaymentRejectedAt
                ?.plusHours(SkladchinaConfirmationPolicy.RECEIPT_WINDOW_HOURS)
                ?.isAfter(OffsetDateTime.now()) == true
        val actionRequired = (item.skladchina.status == SkladchinaStatus.active &&
            item.myStatus == SkladchinaParticipantStatus.pending) || receiptWindowOpen

        return MySkladchinaListItemDto(
            id = item.skladchina.id,
            title = item.skladchina.title,
            clubId = item.skladchina.clubId,
            clubName = item.clubName,
            clubAvatarUrl = item.clubAvatarUrl,
            template = item.skladchina.template.literal,
            paymentMode = item.skladchina.paymentMode.literal,
            totalGoalKopecks = item.skladchina.totalGoalKopecks,
            collectedKopecks = item.collectedKopecks,
            participantCount = item.participantCount,
            paidCount = item.paidCount,
            confirmedCount = item.confirmedCount,
            deadline = item.skladchina.deadline,
            status = item.skladchina.status.literal,
            isOrganizerView = isOrganizerView,
            myStatus = item.myStatus?.literal,
            actionRequired = actionRequired,
            affectsReputation = item.skladchina.affectsReputation
        )
    }

    private fun toParticipantDto(info: SkladchinaParticipantInfo): SkladchinaParticipantDto =
        SkladchinaParticipantDto(
            userId = info.userId,
            firstName = info.firstName,
            lastName = info.lastName,
            avatarUrl = info.avatarUrl,
            expectedAmountKopecks = info.expectedAmountKopecks,
            declaredAmountKopecks = info.declaredAmountKopecks,
            status = info.status.literal,
            paidAt = info.paidAt,
            declineRequested = info.declineRequestedAt != null,
            declineNote = info.declineNote,
            declineRejected = info.declineRejected,
            declineRejectNote = info.declineRejectNote,
            paymentRejectNote = info.paymentRejectNote,
            receiptUrl = info.receiptUrl,
            receiptNote = info.receiptNote,
            disputedAt = info.disputedAt,
            disputeTerminal = info.disputeTerminal
        )
}
