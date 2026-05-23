package com.clubs.skladchina

import com.clubs.generated.jooq.tables.records.SkladchinaParticipantsRecord
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
        paymentMode = record.paymentMode,
        totalGoalKopecks = record.totalGoalKopecks,
        paymentLink = record.paymentLink,
        paymentMethodNote = record.paymentMethodNote,
        deadline = record.deadline,
        affectsReputation = record.affectsReputation ?: false,
        status = record.status!!,
        closedAt = record.closedAt,
        closedBy = record.closedBy,
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
        createdAt = record.createdAt!!
    )

    fun toDetailDto(
        skladchina: Skladchina,
        clubName: String,
        clubAvatarUrl: String?,
        callerUserId: UUID,
        participants: List<SkladchinaParticipantInfo>,
        collectedKopecks: Long
    ): SkladchinaDetailDto {
        val isOrganizerView = skladchina.creatorId == callerUserId
        val myParticipant = participants.firstOrNull { it.userId == callerUserId }
        val paidCount = participants.count { it.status.literal == "paid" }

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
            paymentMode = skladchina.paymentMode.literal,
            totalGoalKopecks = skladchina.totalGoalKopecks,
            collectedKopecks = collectedKopecks,
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
            participants = if (isOrganizerView) participants.map(::toParticipantDto) else null,
            participantCount = participants.size,
            paidCount = paidCount
        )
    }

    fun toMyFeedItemDto(item: MySkladchinaFeedItem, callerUserId: UUID): MySkladchinaListItemDto {
        val isOrganizerView = item.skladchina.creatorId == callerUserId
        val actionRequired = item.skladchina.status.literal == "active" &&
            item.myStatus?.literal == "pending"

        return MySkladchinaListItemDto(
            id = item.skladchina.id,
            title = item.skladchina.title,
            clubId = item.skladchina.clubId,
            clubName = item.clubName,
            clubAvatarUrl = item.clubAvatarUrl,
            paymentMode = item.skladchina.paymentMode.literal,
            totalGoalKopecks = item.skladchina.totalGoalKopecks,
            collectedKopecks = item.collectedKopecks,
            participantCount = item.participantCount,
            paidCount = item.paidCount,
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
            paidAt = info.paidAt
        )
}
