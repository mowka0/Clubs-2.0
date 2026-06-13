package com.clubs.reputation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side P1b Trust derivation. Computes per-club Trust 0-100 and the all-history global
 * aggregate ("надёжен в N из M клубов") ON READ from the ledger via [TrustPolicy].
 *
 * Decay depends on the current time, so nothing here is cached — occurred_at is read fresh each
 * call. Separate from [ReputationService] (which owns the write-side ledger pipeline + recompute):
 * one class = one reason to change.
 */
@Service
class TrustService(
    private val reputationRepository: ReputationRepository
) {

    /**
     * Per-club Trust + the all-history global view for one user. The display gate
     * (ReputationPolicy.isShown(outcomeCount)) and club metadata (name/avatar/role) are applied by
     * the caller at the DTO boundary — this returns the raw computed numbers keyed by club.
     */
    @Transactional(readOnly = true)
    fun computeForUser(userId: UUID, now: OffsetDateTime = OffsetDateTime.now()): UserTrust {
        val clubs = reputationRepository.findTrustOutcomesByUser(userId)
            .groupBy { it.clubId }
            .map { (clubId, rows) ->
                ClubTrust(
                    clubId = clubId,
                    trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
                    outcomeCount = rows.size,
                    lastOccurredAt = rows.maxOf { it.occurredAt }
                )
            }
        val standings = clubs.map { TrustPolicy.ClubStanding(it.trust, it.outcomeCount, it.lastOccurredAt) }
        return UserTrust(perClub = clubs, global = TrustPolicy.global(standings, now))
    }

    /**
     * Per-member Trust for every member of a club that has ledger outcomes there (one batch query,
     * no N+1). Members with no outcomes are absent from the map; the caller renders them "Новичок".
     */
    @Transactional(readOnly = true)
    fun trustForClubMembers(clubId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Map<UUID, Int> =
        reputationRepository.findClubMemberOutcomes(clubId)
            .groupBy { it.userId }
            .mapValues { (_, rows) ->
                TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now)
            }

    /** One member's Trust in one club, or null if they have no ledger outcomes there. */
    @Transactional(readOnly = true)
    fun trustForUserInClub(userId: UUID, clubId: UUID, now: OffsetDateTime = OffsetDateTime.now()): Int? {
        val outcomes = reputationRepository.findTrustOutcomesByUser(userId)
            .filter { it.clubId == clubId }
            .map { TrustPolicy.Outcome(it.kind, it.occurredAt) }
        return if (outcomes.isEmpty()) null else TrustPolicy.perClubTrust(outcomes, now)
    }
}

/** A user's computed Trust in one club. `trust` is always present; the display gate is presentational. */
data class ClubTrust(
    val clubId: UUID,
    val trust: Int,
    val outcomeCount: Int,
    val lastOccurredAt: OffsetDateTime
)

data class UserTrust(
    val perClub: List<ClubTrust>,
    val global: TrustPolicy.GlobalTrust
)
