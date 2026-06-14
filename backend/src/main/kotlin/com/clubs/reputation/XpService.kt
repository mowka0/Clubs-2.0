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
        val stats = computeStats(userId, now)
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
     * §H8 others-tier projection: the GLOBAL level NAME for a batch of users, for display to OTHER
     * members (e.g. the club member list) — one batch query, no N+1. ONLY the level name crosses the
     * boundary: exact XP, weights, thresholds, badges and the raw ledger never leave the server.
     *
     * The level is the user's global account level (independent of any single club's track record),
     * computed identically to [getGamification] (same XP). Returns userId → level name only above the
     * floor level (index ≥ 1, i.e. past "Гость"); level-0 / no-history users are absent so the caller
     * renders no level chip — the same "nothing notable yet" spirit as the self panel hiding at 0 XP.
     */
    @Transactional(readOnly = true)
    fun publicLevelNames(userIds: Collection<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        return reputationRepository.findOutcomesByUsers(userIds)
            .groupBy { it.userId }
            .mapNotNull { (userId, rows) ->
                val idx = XpPolicy.levelIndexFor(XpPolicy.totalXp(xpCountsOnly(rows)))
                if (idx >= 1) userId to XpPolicy.LEVEL_NAMES[idx] else null
            }
            .toMap()
    }

    /** XP-only stats from raw rows: kept counts + distinct kept clubs. Trust fields are irrelevant to
     *  XP (and thus to the level), so they stay 0 — no per-club Trust is computed for the batch. */
    private fun xpCountsOnly(rows: List<UserLedgerOutcome>): XpPolicy.XpStats {
        var ironclad = 0
        var spontaneous = 0
        var skladchinaPaid = 0
        val keptClubs = HashSet<UUID>()
        rows.forEach { o ->
            when (o.kind) {
                ReputationKind.ironclad -> { ironclad++; keptClubs.add(o.clubId) }
                ReputationKind.spontaneous -> { spontaneous++; keptClubs.add(o.clubId) }
                ReputationKind.skladchina_paid -> { skladchinaPaid++; keptClubs.add(o.clubId) }
                else -> Unit
            }
        }
        return XpPolicy.XpStats(ironclad, spontaneous, skladchinaPaid, keptClubs.size, 0, 0)
    }

    private fun computeStats(userId: UUID, now: OffsetDateTime): XpPolicy.XpStats {
        var ironclad = 0
        var spontaneous = 0
        var skladchinaPaid = 0
        var distinctKeptClubs = 0
        var reliableClubs = 0
        var maxTrustWithRecord = 0

        reputationRepository.findTrustOutcomesByUser(userId)
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
