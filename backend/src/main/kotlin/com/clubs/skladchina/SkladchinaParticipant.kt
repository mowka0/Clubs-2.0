package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import java.time.OffsetDateTime
import java.util.UUID

data class SkladchinaParticipant(
    val skladchinaId: UUID,
    val userId: UUID,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: SkladchinaParticipantStatus,
    val paidAt: OffsetDateTime?,
    val declinedAt: OffsetDateTime?,
    val reputationApplied: Boolean,
    val createdAt: OffsetDateTime
)

/**
 * Aggregated row for "my feed" — Skladchina + my-participant + computed counts.
 * Returned from repository to service, mapped to MySkladchinaListItemDto.
 */
data class MySkladchinaFeedItem(
    val skladchina: Skladchina,
    val clubName: String,
    val clubAvatarUrl: String?,
    val myStatus: SkladchinaParticipantStatus?,    // null if user is creator but not participant
    val collectedKopecks: Long,
    val participantCount: Int,
    val paidCount: Int
)

/**
 * Participant row with denormalized user info — for organizer view of SkladchinaPage.
 */
data class SkladchinaParticipantInfo(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val avatarUrl: String?,
    val expectedAmountKopecks: Long?,
    val declaredAmountKopecks: Long?,
    val status: SkladchinaParticipantStatus,
    val paidAt: OffsetDateTime?
)
