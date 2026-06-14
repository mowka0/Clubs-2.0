package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Read-side P1b XP / level / badges derivation. Computes the account-level gamification panel ON
 * READ from the ledger (one [ReputationRepository.findTrustOutcomesByUser] query, the same source
 * as [TrustService]). Pure logic — weights, level thresholds, badge predicates — lives in
 * [XpPolicy]; this class only aggregates the ledger into [XpPolicy.XpStats] and assembles the DTO.
 *
 * XP rewards participation only and never decreases; the owner-in-own-club anti-farm is inherited
 * (no ledger rows there → no XP there). Trust per club is recomputed here only for the trust badges.
 */
@Service
class XpService(
    private val reputationRepository: ReputationRepository
) {

    @Transactional(readOnly = true)
    fun getGamification(userId: UUID, now: OffsetDateTime = OffsetDateTime.now()): GamificationDto {
        val stats = statsForOutcomes(reputationRepository.findTrustOutcomesByUser(userId), now)
        val xp = XpPolicy.totalXp(stats)
        val idx = XpPolicy.levelIndexFor(xp)
        val isMax = idx == XpPolicy.LEVEL_NAMES.lastIndex
        return GamificationDto(
            xp = xp,
            level = idx + 1,
            levelName = XpPolicy.LEVEL_NAMES[idx],
            nextLevelName = if (isMax) null else XpPolicy.LEVEL_NAMES[idx + 1],
            xpIntoLevel = xp - XpPolicy.levelThreshold(idx),
            xpSpanToNext = if (isMax) null else XpPolicy.levelThreshold(idx + 1) - XpPolicy.levelThreshold(idx),
            badges = XpPolicy.badgesFor(stats).map { BadgeDto(it.id, it.name, it.family.name) }
        )
    }

    /**
     * Global level (name + 0-based index) from a pre-fetched outcome list — the projection shown to
     * OTHERS (e.g. the applicant pill on the review card), without XP/progress/badges. Outcome-based
     * so the batch applicant path ([ApplicantSignalService]) fetches once for many users.
     */
    fun levelForOutcomes(outcomes: List<ClubLedgerOutcome>, now: OffsetDateTime = OffsetDateTime.now()): LevelInfo {
        val idx = XpPolicy.levelIndexFor(XpPolicy.totalXp(statsForOutcomes(outcomes, now)))
        return LevelInfo(level = idx + 1, name = XpPolicy.LEVEL_NAMES[idx], index = idx)
    }

    private fun statsForOutcomes(outcomes: List<ClubLedgerOutcome>, now: OffsetDateTime): XpPolicy.XpStats {
        var ironclad = 0
        var spontaneous = 0
        var skladchinaPaid = 0
        var distinctKeptClubs = 0
        var reliableClubs = 0
        var maxTrustWithRecord = 0

        outcomes
            .groupBy { it.clubId }
            .forEach { (_, rows) ->
                var clubHasKept = false
                rows.forEach { o ->
                    when (o.kind) {
                        ReputationKind.ironclad -> { ironclad++; clubHasKept = true }
                        ReputationKind.spontaneous -> { spontaneous++; clubHasKept = true }
                        ReputationKind.skladchina_paid -> { skladchinaPaid++; clubHasKept = true }
                        else -> Unit
                    }
                }
                if (clubHasKept) distinctKeptClubs++
                // Trust badges only consider clubs with a shown track record (same gate as the UI).
                if (rows.size >= ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY) {
                    val trust = TrustPolicy.perClubTrust(rows.map { TrustPolicy.Outcome(it.kind, it.occurredAt) }, now)
                    if (XpPolicy.isReliable(trust)) reliableClubs++
                    if (trust > maxTrustWithRecord) maxTrustWithRecord = trust
                }
            }

        return XpPolicy.XpStats(
            ironcladCount = ironclad,
            spontaneousCount = spontaneous,
            skladchinaPaidCount = skladchinaPaid,
            distinctKeptClubs = distinctKeptClubs,
            reliableClubs = reliableClubs,
            maxTrustWithRecord = maxTrustWithRecord
        )
    }
}

/** The `others` level projection: 1-based level, its name, and the 0-based index (for tiering). */
data class LevelInfo(val level: Int, val name: String, val index: Int)
