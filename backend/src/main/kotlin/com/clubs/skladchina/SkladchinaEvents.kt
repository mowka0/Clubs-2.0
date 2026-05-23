package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaStatus
import java.util.UUID

/**
 * Published after a skladchina is created and the transaction has committed.
 * Listener (SkladchinaBotNotifier) sends DMs to participants.
 * See PaymentNotificationHandler for the canonical TransactionalEventListener pattern.
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
    val participantUserIds: List<UUID>
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
    val affectsReputation: Boolean
)
