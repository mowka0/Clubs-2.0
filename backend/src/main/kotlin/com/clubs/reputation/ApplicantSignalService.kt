package com.clubs.reputation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Cross-club applicant signal for the organizer review surface: the global "надёжен в N из M клубов"
 * aggregate plus the global level projection (name + tier). Computed ON READ from the ledger in ONE
 * batch query for all applicants, then delegated to the canonical [TrustService.globalForOutcomes]
 * and [XpService.levelForOutcomes] — no per-applicant N+1, no duplicated Trust/XP formula.
 *
 * Owner-blind by construction: the ledger has no rows for an owner's own club (anti-farm rule 1), so
 * this signal reflects the PARTICIPANT track record, not organizer experience. The review card frames
 * it as «Активность на платформе» accordingly.
 */
@Service
class ApplicantSignalService(
    private val reputationRepository: ReputationRepository,
    private val trustService: TrustService,
    private val xpService: XpService
) {

    @Transactional(readOnly = true)
    fun signalsFor(
        userIds: Collection<UUID>,
        now: OffsetDateTime = OffsetDateTime.now()
    ): Map<UUID, ApplicantSignal> {
        if (userIds.isEmpty()) return emptyMap()
        val outcomesByUser = reputationRepository.findOutcomesByUserIds(userIds)
        return userIds.associateWith { userId ->
            val outcomes = outcomesByUser[userId].orEmpty()
            val global = trustService.globalForOutcomes(outcomes, now)
            val level = xpService.levelForOutcomes(outcomes, now)
            ApplicantSignal(
                reliableClubs = global.reliableClubs,
                trackRecordClubs = global.trackRecordClubs,
                level = level.level,
                levelName = level.name,
                levelTier = tierFor(level.index)
            )
        }
    }

    /** 0-based level index → pill tier. Top tier = Столп сообщества/Легенда/Амбассадор (gold pill). */
    private fun tierFor(levelIndex: Int): String = when {
        levelIndex >= 7 -> "top"
        levelIndex >= 3 -> "mid"
        else -> "base"
    }
}

/**
 * Applicant's cross-club reputation as the organizer review card shows it:
 *  - reliableClubs / trackRecordClubs — the "N из M" donut (clubs where Trust ≥ reliable, of clubs
 *    with a shown track record). 0/0 when the applicant has no track record yet.
 *  - level / levelName — the global gamification level (others projection).
 *  - levelTier — "base" | "mid" | "top" for the pill color.
 */
data class ApplicantSignal(
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val level: Int,
    val levelName: String,
    val levelTier: String
) {
    companion object {
        /** Default for an applicant with no ledger outcomes: no track record, level 1 (Гость). */
        val EMPTY = ApplicantSignal(
            reliableClubs = 0,
            trackRecordClubs = 0,
            level = 1,
            levelName = XpPolicy.LEVEL_NAMES.first(),
            levelTier = "base"
        )
    }
}
