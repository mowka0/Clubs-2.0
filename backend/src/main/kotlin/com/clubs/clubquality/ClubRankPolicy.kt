package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.pow

/**
 * L3 hidden rank — pure formula (the heart of the club-quality track). A composite of four
 * distinct-credible-account axes minus owner-blind negatives, gated by a credibility-weighted min-K
 * floor. INTERNAL: nothing here is ever serialized; the only externally-visible derivative is the
 * boolean "★ Топ-5 в категории".
 *
 * Design contract: docs/backlog/club-quality-gamification.md §1–8, docs/modules/club-quality.md §10.
 *
 * **Anti-farm spine (every constant serves it):**
 *  1. Owner-authored data never reaches L3 — the repository feeds only member-driven signals.
 *  2. Distinct-CREDIBLE-account weighting: every axis is `Σ credibility(account)`, never raw counts.
 *  3. Absolutes, never ratios (a ring trivially makes 100% of any %).
 *  4. Recency-decay via behaviour time (`occurred_at`), so nothing banks forever.
 *  5. Credibility-WEIGHTED existence gate: a ring of cheap accounts cannot manufacture a rank.
 *  6. Owner-concentration collapse + per-owner category cap: one operator cannot self-populate a
 *     category (the "category manufacturing" Sybil vector).
 *
 * **PROVISIONAL:** weights/anchors/thresholds are principled defaults, NOT calibrated — calibration
 * on a ~10-club prod is impossible. They live here so a future calibration touches one file, no SQL.
 */
object ClubRankPolicy {

    // ---- Existence gate (does the club have a rank at all?) ----

    /**
     * Credibility-WEIGHTED min-K: a club is ranked only if `Σ credibility(credibleCore) ≥ EFFECTIVE_K`.
     * Weighted (not a head-count): a fresh single-club puppet contributes ~0.24, so a ring needs ~22+
     * accounts, not 8 — the only number that actually scales attacker cost. Below the gate ⇒ UNRANKED.
     */
    const val EFFECTIVE_K = 8.0

    /** Floor of an account's credibility contribution; an account whose raw credibility is below this
     *  does not count toward the gate or any axis at all. */
    const val CRED_MIN = 0.2

    // ---- Recency decay (TrustPolicy math: 0.5^(age/halfLife), future-dated → weight 1.0) ----

    /** Positives lose half their weight after this many days (fame leaks out slowly). */
    const val HALF_LIFE_POS_DAYS = 120.0

    /** Negatives decay faster than positives — a recovery valve (a fixed club isn't punished forever). */
    const val HALF_LIFE_NEG_DAYS = 90.0

    // ---- Per-axis absolute floors (axis below its floor contributes 0, never a fraction) ----

    const val PAY_MIN = 3 // distinct Stars payers
    const val VOTE_MIN = 8 // distinct member voters
    const val EVENT_MIN = 2 // events with ≥4 distinct qualifying attended

    // ---- Backbone qualification (anti-farm shape of the core signal) ----

    /** A core member must have attended at least this many DISTINCT events. */
    const val CORE_MIN_EVENTS = 2

    /** Those qualifying attendances must span at least this many days — a single batch-day farm
     *  (everyone marked one evening) does not manufacture diversity. */
    const val MIN_EVENT_GAP_DAYS = 7L

    /** An event counts toward LiveActivity only with at least this many distinct qualifying attendees. */
    const val EVENT_MIN_ATTENDEES = 4

    // ---- Read windows ----

    /** DemandResponsiveness counts member voters within this trailing window. */
    const val DEMAND_WINDOW_DAYS = 90L

    /** Signals older than this are not read at all (cheap scan; weight is < 0.13 by then anyway). */
    const val HARD_CUTOFF_DAYS = 365L

    /** A payer who left within this many days of paying is the "paid then ghosted" scam signature. */
    const val SCAM_LEFT_WINDOW_DAYS = 14L

    // ---- Composite weights (paid club). Money + core = 65% — the most expensive axes to farm. ----

    const val W_DIVERSITY = 0.35
    const val W_PAYING = 0.30
    const val W_DEMAND = 0.20
    const val W_ACTIVITY = 0.15

    // Free club: PayingRetention is off; its 0.30 redistributes proportionally onto the other three.
    const val W_DIVERSITY_FREE = W_DIVERSITY / (1 - W_PAYING) // 0.50
    const val W_DEMAND_FREE = W_DEMAND / (1 - W_PAYING) // ≈0.286
    const val W_ACTIVITY_FREE = W_ACTIVITY / (1 - W_PAYING) // ≈0.214

    // ---- Saturating normalization anchors (so axes in different units add commensurably) ----
    // norm(x) = 1 − 0.5^(x/anchor): reaches 0.5 at the anchor, saturates toward 1.

    const val ANCHOR_DIVERSITY = 12.0
    const val ANCHOR_PAYING = 8.0
    const val ANCHOR_DEMAND = 12.0
    const val ANCHOR_ACTIVITY = 6.0

    /** A payer who left ≤14d after paying (scam signature) contributes at this fraction. */
    const val SCAM_PAYER_FACTOR = 0.5

    // ---- Credibility weight buckets ----

    const val AGE_W_MATURE = 1.0 // ≥180d
    const val AGE_W_ESTABLISHED = 0.8 // 90–180d
    const val AGE_W_RECENT = 0.6 // 30–90d
    const val AGE_W_FRESH = 0.4 // <30d

    const val SIGNAL_W_BASE = 0.6
    const val SIGNAL_W_USERNAME = 0.2
    const val SIGNAL_W_AVATAR = 0.2

    const val FOOTPRINT_W_BROAD = 1.0 // ≥3 distinct owners
    const val FOOTPRINT_W_SOME = 0.85 // 2 owners
    const val FOOTPRINT_W_SINGLE = 0.6 // 1 owner (sock-puppet sits in one operator's clubs)

    /** If ≥ this share of an account's footprint is in THIS club's owner's clubs, it is not independent
     *  evidence of quality → credibility pressed to CRED_MIN (the lightweight co-occurrence check). */
    const val OWNER_CONCENTRATION_THRESHOLD = 0.6

    // ---- Negative penalties (subtracted from the [0,1] base, capped, decayed) ----
    // Magnitudes are on the normalized [0,1] scale so a few incidents dent the score without auto-zeroing.

    const val DISPUTE_W = 0.05
    const val DISPUTE_CAP = 0.30
    const val GHOST_W = 0.07
    const val GHOST_CAP = 0.40
    const val SOFT_W = 0.03 // each of auto-reject / skladchina-ghost
    const val SOFT_CAP = 0.20 // joint cap on the two soft penalties

    // ---- Multipliers ----

    /** "Too clean at volume" dampener: a sizeable club with zero disputes/ghosting/churn over the
     *  window is statistically suspicious (a smooth ring). One trigger, −0.1. */
    const val ANOMALY_CLEAN_MIN_CORE = 10
    const val ANOMALY_STEP = 0.1
    const val ANOMALY_FLOOR = 0.7

    /** A club younger than this gets a proportional weight (thin history isn't full evidence). */
    const val TENURE_FULL_DAYS = 90.0

    // ---- "★ Топ-5 в категории" badge gates ----

    /** A category needs at least this many ranked clubs (after the per-owner cap) before the badge can
     *  appear — you cannot be "top-5" of three. Keeps the badge a real selection, not a participation
     *  trophy. Sized deliberately so it is RARE on a small prod (honest absence over false stars). */
    const val MIN_CATEGORY_SIZE = 6

    /** Absolute score floor: even at position ≤5, a club barely over the K gate falls below this and
     *  gets no badge. The badge means "good AND top-of-category", not "top of a thin category". */
    const val BADGE_SCORE_FLOOR = 0.20

    /** A position-≤5 club must beat the 6th club by at least this margin; if #5 and #6 are
     *  indistinguishable, no real selection happened → no badge at the boundary. */
    const val SELECTIVITY_EPS = 0.05

    /** Until at least this many clubs are ranked prod-wide, the badge is suppressed for ALL clubs — the
     *  explicit "rank not meaningful yet" kill-switch (separate from the deploy feature flag). */
    const val GLOBAL_RANK_FLOOR = 8

    /** Owner-concentration co-occurrence collapse is the v1 defense; the full cross-club graph is a
     *  documented stub (=1.0) until a real attack triggers it (design §5). */
    const val CO_OCCURRENCE_COLLAPSE = 1.0

    /** No secondary ownership market yet → transfer probation is a documented stub. */
    const val TRANSFER_PROBATION = 1.0

    // ---- Pure helpers ----

    /** Recency decay weight in (0,1]. Future-dated rows (clock skew) are treated as fresh (weight 1). */
    fun decay(occurredAt: OffsetDateTime, now: OffsetDateTime, halfLifeDays: Double): Double {
        val ageDays = (now.toEpochSecond() - occurredAt.toEpochSecond()) / 86_400.0
        return 0.5.pow((if (ageDays < 0.0) 0.0 else ageDays) / halfLifeDays)
    }

    /** Saturating normalization to [0,1): reaches 0.5 at [anchor]. */
    fun norm(x: Double, anchor: Double): Double = 1.0 - 0.5.pow(x / anchor)

    private fun ageW(createdAt: OffsetDateTime, now: OffsetDateTime): Double {
        val ageDays = (now.toEpochSecond() - createdAt.toEpochSecond()) / 86_400.0
        return when {
            ageDays >= 180 -> AGE_W_MATURE
            ageDays >= 90 -> AGE_W_ESTABLISHED
            ageDays >= 30 -> AGE_W_RECENT
            else -> AGE_W_FRESH
        }
    }

    private fun signalW(input: CredibilityInput): Double =
        SIGNAL_W_BASE +
            (if (input.hasUsername) SIGNAL_W_USERNAME else 0.0) +
            (if (input.hasAvatar) SIGNAL_W_AVATAR else 0.0)

    private fun footprintW(footprintByOwner: Map<UUID, Int>): Double =
        when (footprintByOwner.size) {
            0, 1 -> FOOTPRINT_W_SINGLE
            2 -> FOOTPRINT_W_SOME
            else -> FOOTPRINT_W_BROAD
        }

    /** Share of an account's kept-outcome clubs that belong to [ownerId]. 0 when the account has no
     *  footprint (a member with no kept outcome anywhere yet). */
    private fun ownerConcentration(footprintByOwner: Map<UUID, Int>, ownerId: UUID): Double {
        val total = footprintByOwner.values.sum()
        if (total == 0) return 0.0
        return (footprintByOwner[ownerId] ?: 0).toDouble() / total
    }

    /**
     * Credibility weight of one account toward THIS club, in [0,1]. An account whose footprint is
     * dominated by this owner's own clubs is pressed to [CRED_MIN] (not independent evidence). The
     * result is NOT floored otherwise — the caller drops accounts below [CRED_MIN] from the gate/axes.
     */
    fun credibility(input: CredibilityInput, ownerId: UUID, now: OffsetDateTime): Double {
        if (ownerConcentration(input.footprintByOwner, ownerId) >= OWNER_CONCENTRATION_THRESHOLD) {
            return CRED_MIN
        }
        return (ageW(input.createdAt, now) * signalW(input) * footprintW(input.footprintByOwner))
            .coerceAtMost(1.0)
    }

    /** Decayed, credibility-weighted sum over a distinct-account axis, after the per-account credibility
     *  floor. [extraFactor] applies a per-account adjustment (e.g. scam-payer ×0.5); default 1.0. */
    private fun weightedAxis(
        accounts: List<AccountOutcome>,
        cred: Map<UUID, Double>,
        now: OffsetDateTime,
        extraFactor: (UUID) -> Double = { 1.0 },
    ): Double = accounts.sumOf { a ->
        val c = cred[a.userId] ?: 0.0
        if (c < CRED_MIN) 0.0 else c * decay(a.occurredAt, now, HALF_LIFE_POS_DAYS) * extraFactor(a.userId)
    }

    private fun decayedSum(times: List<OffsetDateTime>, now: OffsetDateTime, halfLife: Double): Double =
        times.sumOf { decay(it, now, halfLife) }

    /**
     * Compute the L3 rank for one club. [credibilityInputs] is keyed by userId (shared across clubs).
     * Returns the stored outcome; [ClubRank.isRanked] = passed the credibility-weighted K gate.
     */
    fun computeRank(
        signals: ClubRankSignals,
        credibilityInputs: Map<UUID, CredibilityInput>,
        now: OffsetDateTime,
    ): ClubRank {
        val cred: Map<UUID, Double> = credibilityInputs.values.associate {
            it.userId to credibility(it, signals.ownerId, now)
        }

        // Existence gate: Σ credibility over the credible core (each account ≥ CRED_MIN).
        val effectiveK = signals.core.sumOf { (cred[it.userId] ?: 0.0).let { c -> if (c < CRED_MIN) 0.0 else c } }
        val isRanked = effectiveK >= EFFECTIVE_K

        if (!isRanked) {
            return ClubRank(signals.clubId, signals.ownerId, signals.category, 0.0, false, effectiveK)
        }

        // Axes — absolutes, decayed, credibility-weighted; below the per-axis floor ⇒ 0.
        // Diversity has no separate head-count floor: the credibility-weighted gate (effectiveK ≥
        // EFFECTIVE_K) already guarantees a real core, and a count floor would reintroduce exactly the
        // count-based gate the design rejected.
        val diversityRaw = weightedAxis(signals.core, cred, now)
        // payers = first-payment (subscription) accounts; renewers = the disjoint renewal loyalty bonus.
        // The two populations are partitioned in the repository, so a renewer is never counted twice.
        val payingRaw = if (signals.isPaid && signals.payers.size >= PAY_MIN) {
            weightedAxis(signals.payers, cred, now) { uid ->
                if (uid in signals.scamPayers) SCAM_PAYER_FACTOR else 1.0
            } + weightedAxis(signals.renewers, cred, now)
        } else {
            0.0
        }
        val demandRaw = if (signals.voters.size >= VOTE_MIN) weightedAxis(signals.voters, cred, now) else 0.0
        val activityRaw = if (signals.qualityEvents.size >= EVENT_MIN) {
            decayedSum(signals.qualityEvents, now, HALF_LIFE_POS_DAYS)
        } else {
            0.0
        }

        val base = if (signals.isPaid) {
            W_DIVERSITY * norm(diversityRaw, ANCHOR_DIVERSITY) +
                W_PAYING * norm(payingRaw, ANCHOR_PAYING) +
                W_DEMAND * norm(demandRaw, ANCHOR_DEMAND) +
                W_ACTIVITY * norm(activityRaw, ANCHOR_ACTIVITY)
        } else {
            W_DIVERSITY_FREE * norm(diversityRaw, ANCHOR_DIVERSITY) +
                W_DEMAND_FREE * norm(demandRaw, ANCHOR_DEMAND) +
                W_ACTIVITY_FREE * norm(activityRaw, ANCHOR_ACTIVITY)
        }

        val disputePenalty = (decayedSum(signals.disputes, now, HALF_LIFE_NEG_DAYS) * DISPUTE_W)
            .coerceAtMost(DISPUTE_CAP)
        val ghostPenalty = (decayedSum(signals.ghosting, now, HALF_LIFE_NEG_DAYS) * GHOST_W)
            .coerceAtMost(GHOST_CAP)
        val softPenalty = (
            decayedSum(signals.autoRejects, now, HALF_LIFE_NEG_DAYS) * SOFT_W +
                decayedSum(signals.skladchinaGhosts, now, HALF_LIFE_NEG_DAYS) * SOFT_W
            ).coerceAtMost(SOFT_CAP)

        val penalized = base - disputePenalty - ghostPenalty - softPenalty

        val anomalyMultiplier = anomalyMultiplier(signals)
        val tenureFactor = ((now.toEpochSecond() - signals.clubCreatedAt.toEpochSecond()) / 86_400.0 /
            TENURE_FULL_DAYS).coerceIn(0.0, 1.0)

        val rankScore = (penalized * anomalyMultiplier * tenureFactor *
            CO_OCCURRENCE_COLLAPSE * TRANSFER_PROBATION).coerceAtLeast(0.0)

        return ClubRank(signals.clubId, signals.ownerId, signals.category, rankScore, true, effectiveK)
    }

    /** Anomaly multiplier in [ANOMALY_FLOOR, 1.0]. v1 = one trigger ("too clean at volume"). */
    private fun anomalyMultiplier(signals: ClubRankSignals): Double {
        var m = 1.0
        val tooCleanAtVolume = signals.core.size >= ANOMALY_CLEAN_MIN_CORE &&
            signals.disputes.isEmpty() && signals.ghosting.isEmpty() && signals.churnEvents90d == 0
        if (tooCleanAtVolume) m -= ANOMALY_STEP
        return m.coerceAtLeast(ANOMALY_FLOOR)
    }

    /**
     * The clubs that earn "★ Топ-5 в категории", from all ranked clubs prod-wide. Applies, in order:
     * the deploy feature flag, the global rank floor kill-switch, per-owner collapse (max 1 club per
     * owner per category — kills category manufacturing), MIN_CATEGORY_SIZE, position ≤5, the absolute
     * score floor, and the selectivity margin over the 6th club. Returns the badged clubIds.
     */
    fun topInCategory(ranked: List<RankedClub>, badgeEnabled: Boolean): Set<UUID> {
        if (!badgeEnabled) return emptySet()
        if (ranked.size < GLOBAL_RANK_FLOOR) return emptySet()

        val badged = mutableSetOf<UUID>()
        ranked.groupBy { it.category }.forEach { (_, clubsInCategory) ->
            // Per-owner collapse: keep only each owner's best club, so one operator can't populate it.
            val perOwnerBest = clubsInCategory
                .groupBy { it.ownerId }
                .map { (_, clubs) -> clubs.maxBy { it.rankScore } }
                .sortedByDescending { it.rankScore }

            if (perOwnerBest.size < MIN_CATEGORY_SIZE) return@forEach

            val sixthScore = perOwnerBest[5].rankScore // exists: size ≥ MIN_CATEGORY_SIZE ≥ 6
            perOwnerBest.take(5).forEach { club ->
                if (club.rankScore >= BADGE_SCORE_FLOOR && club.rankScore - sixthScore >= SELECTIVITY_EPS) {
                    badged += club.clubId
                }
            }
        }
        return badged
    }
}
