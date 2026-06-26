package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.util.UUID

/**
 * Published after a skladchina is created and the transaction has committed.
 * Listener (SkladchinaBotNotifier) sends DMs to participants.
 * See SkladchinaBotNotifier for the canonical TransactionalEventListener pattern.
 */
data class SkladchinaCreatedEvent(
    val skladchinaId: UUID,
    val clubId: UUID,
    val clubName: String,
    val title: String,
    val description: String?,
    val paymentLink: String,
    val paymentMode: String,
    val totalGoalKopecks: Long?,
    val deadline: java.time.OffsetDateTime,
    val affectsReputation: Boolean,
    val participantUserIds: List<UUID>
)

/**
 * Published after a participant opens a decline request (REQUIRES_APPROVAL templates, V28) and the
 * transaction has committed. Listener DMs the organizer with the requester + reason and a button
 * to the skladchina page.
 */
data class SkladchinaDeclineRequestedEvent(
    val skladchinaId: UUID,
    val creatorId: UUID,
    val requesterUserId: UUID,
    val clubName: String,
    val title: String,
    val reason: String
)

/**
 * Published after the organizer REJECTS a decline request (V29) and the transaction has committed.
 * Listener DMs the rejected participant with the organizer's reason and a button to the skladchina
 * page (they must still pay).
 */
data class SkladchinaDeclineRejectedEvent(
    val skladchinaId: UUID,
    val participantUserId: UUID,
    val clubName: String,
    val title: String,
    val reason: String
)

/**
 * Published after a skladchina is closed (manual, goal-reached, all-answered, or
 * scheduler auto-close) and the transaction has committed. Listener notifies
 * the creator with a summary.
 */
data class SkladchinaClosedEvent(
    val skladchinaId: UUID,
    val creatorId: UUID,
    val clubName: String,
    val title: String,
    val finalStatus: SkladchinaStatus,
    val collectedKopecks: Long,
    val totalGoalKopecks: Long?,
    val paidCount: Int,
    val participantCount: Int,
    val affectsReputation: Boolean,
    /**
     * Participants who stayed silent until the deadline and just received the -40
     * ledger entry. Non-empty ONLY for a reputation-affecting close at/after the
     * deadline — the notifier DMs each of them about the penalty (launch-blocker
     * notification #3 of the redesign).
     */
    val expiredParticipantUserIds: List<UUID> = emptyList()
)
