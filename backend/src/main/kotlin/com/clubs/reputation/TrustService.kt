package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
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
        val outcomes = reputationRepository.findTrustOutcomesByUser(userId)
        val clubs = outcomes
            .groupBy { it.clubId }
            .map { (clubId, rows) ->
                ClubTrust(
                    clubId = clubId,
                    trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
                    outcomeCount = rows.size,
                    lastOccurredAt = rows.maxOf { it.occurredAt }
                )
            }
        return UserTrust(perClub = clubs, global = globalForOutcomes(outcomes, now))
    }

    /**
     * The all-history global aggregate ("надёжен в N из M клубов") from a pre-fetched outcome list.
     * Single place that derives [TrustPolicy.GlobalTrust] from outcomes — shared by the self overview
     * ([computeForUser]) and the batch applicant signal ([ApplicantSignalService]), which fetches once
     * for many users and so must not re-query per user.
     */
    fun globalForOutcomes(
        outcomes: List<ClubLedgerOutcome>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): TrustPolicy.GlobalTrust {
        val standings = outcomes.groupBy { it.clubId }.map { (_, rows) ->
            TrustPolicy.ClubStanding(
                trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
                outcomeCount = rows.size,
                lastOccurredAt = rows.maxOf { it.occurredAt }
            )
        }
        return TrustPolicy.global(standings, now)
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

    /**
     * One member's per-club reputation for the member card — Trust + the reputation-affecting
     * skladchina record — from a SINGLE ledger read. null if they have no ledger outcomes there.
     * Both rings come from one query: no second scan of the same (user, club) ledger.
     */
    @Transactional(readOnly = true)
    fun clubSummary(userId: UUID, clubId: UUID, now: OffsetDateTime = OffsetDateTime.now()): ClubReputationSummary? {
        val outcomes = reputationRepository.findTrustOutcomesByUser(userId).filter { it.clubId == clubId }
        if (outcomes.isEmpty()) return null
        return ClubReputationSummary(
            trust = TrustPolicy.perClubTrust(outcomes.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now),
            skladchinaPaid = outcomes.count { it.kind == ReputationKind.skladchina_paid },
            skladchinaTotal = outcomes.count {
                it.kind == ReputationKind.skladchina_paid || it.kind == ReputationKind.skladchina_expired
            }
        )
    }
}

/**
 * One member's per-club reputation as the member card shows it, all from one ledger read.
 *  - trust          = per-club Trust 0-100.
 *  - skladchinaPaid / skladchinaTotal = reputation-affecting skladchina record (paid / paid+expired).
 */
data class ClubReputationSummary(
    val trust: Int,
    val skladchinaPaid: Int,
    val skladchinaTotal: Int
)

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
