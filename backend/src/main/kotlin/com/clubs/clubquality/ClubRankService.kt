package com.clubs.clubquality

import com.clubs.reputation.LedgerReadPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * L3 hidden-rank service. Gathers raw signals + credibility inputs, delegates ALL scoring to
 * [ClubRankPolicy], and stores the result. Reads the reputation ledger only through [LedgerReadPort]
 * (never a Trust type) — so the structural invariant *club-L3 ≠ average member-Trust* holds by
 * construction. Logs only counts, never scores (a score in logs is a breakdown leak, security.md).
 */
@Service
class ClubRankService(
    private val clubRankRepository: ClubRankRepository,
    private val ledgerReadPort: LedgerReadPort,
    @Value("\${club.rank.badge-enabled:false}") private val badgeEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(ClubRankService::class.java)

    /** Full recompute of every active club's rank (the scheduler's unit of work). */
    @Transactional
    fun recomputeAll() {
        val now = OffsetDateTime.now()
        val signals = clubRankRepository.findRankSignals(now)
        if (signals.isEmpty()) return

        val userIds = signals.flatMap { it.core + it.payers + it.renewers + it.voters }
            .map { it.userId }.toSet()
        val profiles = clubRankRepository.findUserProfiles(userIds)
        val footprint = ledgerReadPort.footprintByUser(userIds)
        val credibilityInputs = profiles.mapValues { (userId, p) ->
            CredibilityInput(userId, p.createdAt, p.hasUsername, p.hasAvatar, footprint[userId].orEmpty())
        }

        val ranks = signals.map { ClubRankPolicy.computeRank(it, credibilityInputs, now) }
        clubRankRepository.upsertRanks(ranks)
        log.info("Club-rank recompute: {} clubs, {} ranked", ranks.size, ranks.count { it.isRanked })
    }

    /**
     * Of the given clubs, which earn "★ Топ-5 в категории". Empty unless the deploy feature flag is on
     * AND the global rank floor is met (both enforced in [ClubRankPolicy.topInCategory]). This is the
     * ONLY thing the rank ever exposes — a boolean set, never a score.
     */
    @Transactional(readOnly = true)
    fun badgedAmong(clubIds: Collection<UUID>): Set<UUID> {
        // Early-out avoids the ranked-clubs read on the common (flag-off / empty) path. The flag is the
        // authority inside topInCategory too — the double-check is intentional defence-in-depth.
        if (!badgeEnabled || clubIds.isEmpty()) return emptySet()
        val badged = ClubRankPolicy.topInCategory(clubRankRepository.findRankedClubs(), badgeEnabled)
        return badged.intersect(clubIds.toSet())
    }
}
