package com.clubs.reputation

import com.clubs.generated.jooq.enums.ReputationKind
import java.time.OffsetDateTime
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * P1b Trust 0-100 — pure formula. A **Bayesian, recency-decayed fraction of KEPT promises**,
 * classified BY KIND (never by points: V18 backfilled stale magnitudes, so points lie across
 * the V18 boundary; kept/broke-by-kind is magnitude-independent).
 *
 * Design + simulated contract numbers: docs/modules/reputation-v2.md § P1b,
 * docs/backlog/p1b-trust-handoff.md, reputation-v2-redesign.md.
 *
 * Visibility: every constant here is `internal` (§9.1) — never surfaced to users. The UI shows
 * only the resulting 0-100 number (gated by ReputationPolicy.isShown) and the "N из M" aggregate.
 *
 * Decay is computed ON READ from occurred_at (it changes with time → it is never cached). The
 * non-decay kept/broke counts in user_club_reputation (V25) are a cheap reference, not this number.
 */
object TrustPolicy {

    // --- per-club Trust (all internal) ---
    /** Optimistic prior: a user with no track record starts at PRIOR (→ Trust 85), not 0/50. */
    const val PRIOR = 0.85
    /** Bayesian strength: ~K prior outcomes. Larger = more forgiving of the first slips. */
    const val K = 3.0
    /** Asymmetry: a broken promise weighs ASYM× a kept one (intent↔action). */
    const val ASYM = 2.0
    /** A penalty/credit loses half its weight after this many days (recovery valve). */
    const val HALF_LIFE_DAYS = 90.0

    // --- global aggregate "надёжен в N из M клубов" (all internal) ---
    /** Per-club Trust at or above this counts the club toward N (reliable). */
    const val RELIABLE_THRESHOLD = 70
    /** Water-filling cap: one club contributes at most this share of the global number. */
    const val CLUB_WEIGHT_CAP = 0.5
    /** Volume factor outcome/(outcome+VOLUME_K): few outcomes weigh less in the global mean. */
    const val VOLUME_K = 3.0
    /** Global recency: a club's contribution halves after this many days of inactivity. */
    const val GLOBAL_HALF_LIFE_DAYS = 365.0

    enum class TrustClass { KEPT, BROKE, NEUTRAL }

    fun classOf(kind: ReputationKind): TrustClass = when (kind) {
        ReputationKind.ironclad, ReputationKind.spontaneous, ReputationKind.skladchina_paid -> TrustClass.KEPT
        ReputationKind.no_show, ReputationKind.spectator, ReputationKind.skladchina_expired -> TrustClass.BROKE
        // confirmed_unresolved (disputed/unmarked) and historic skladchina_declined are neutral:
        // excluded from the denominator — they are neither a kept nor a broken promise.
        ReputationKind.confirmed_unresolved, ReputationKind.skladchina_declined -> TrustClass.NEUTRAL
    }

    /** One reputational outcome from the ledger. */
    data class Outcome(val kind: ReputationKind, val occurredAt: OffsetDateTime)

    private fun decay(occurredAt: OffsetDateTime, now: OffsetDateTime, halfLifeDays: Double): Double {
        val ageDays = (now.toEpochSecond() - occurredAt.toEpochSecond()) / 86_400.0
        // A future-dated row (clock skew) is treated as fresh, never as a >1 weight.
        return 0.5.pow((if (ageDays < 0.0) 0.0 else ageDays) / halfLifeDays)
    }

    /** The Bayesian core, shared by the Kotlin path and the SQL path (which pre-sums the weights). */
    fun trustFromWeights(keptWeight: Double, brokeWeight: Double): Int =
        (100.0 * (keptWeight + K * PRIOR) / (keptWeight + ASYM * brokeWeight + K)).roundToInt()

    /** Per-club Trust 0-100 from raw outcomes. Always returns a number; the display gate
     *  (ReputationPolicy.isShown(outcomeCount)) decides whether the UI shows it. */
    fun perClubTrust(outcomes: List<Outcome>, now: OffsetDateTime): Int {
        var keptW = 0.0
        var brokeW = 0.0
        for (o in outcomes) when (classOf(o.kind)) {
            TrustClass.KEPT -> keptW += decay(o.occurredAt, now, HALF_LIFE_DAYS)
            TrustClass.BROKE -> brokeW += decay(o.occurredAt, now, HALF_LIFE_DAYS)
            TrustClass.NEUTRAL -> Unit
        }
        return trustFromWeights(keptW, brokeW)
    }

    /** A user's standing in one club, as the global aggregate sees it. */
    data class ClubStanding(val trust: Int, val outcomeCount: Int, val lastOccurredAt: OffsetDateTime)

    /** Global view. score is null when there is no track record anywhere (M == 0). */
    data class GlobalTrust(val reliableClubs: Int, val trackRecordClubs: Int, val score: Int?)

    /**
     * Global "надёжен в N из M клубов" over ALL clubs with a track record (incl. left clubs).
     * M = clubs with outcomeCount >= minOutcomes; N = of those, Trust >= RELIABLE_THRESHOLD.
     * score = diversity/recency-weighted mean of per-club Trust, each club's normalized weight
     * capped at CLUB_WEIGHT_CAP (water-filling) so one club cannot hijack the number. null when M == 0.
     */
    fun global(
        standings: List<ClubStanding>,
        now: OffsetDateTime,
        minOutcomes: Int = ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY
    ): GlobalTrust {
        val tracked = standings.filter { it.outcomeCount >= minOutcomes }
        if (tracked.isEmpty()) return GlobalTrust(reliableClubs = 0, trackRecordClubs = 0, score = null)

        val n = tracked.count { it.trust >= RELIABLE_THRESHOLD }
        val rawWeights = tracked.map { s ->
            val volume = s.outcomeCount.toDouble() / (s.outcomeCount + VOLUME_K)
            volume * decay(s.lastOccurredAt, now, GLOBAL_HALF_LIFE_DAYS)
        }
        val weights = capNormalize(rawWeights, CLUB_WEIGHT_CAP)
        val score = tracked.indices.sumOf { weights[it] * tracked[it].trust }.roundToInt()
        return GlobalTrust(reliableClubs = n, trackRecordClubs = tracked.size, score = score)
    }

    /**
     * Normalize weights to sum 1 with no element exceeding [cap] (water-filling): clamp the
     * over-cap elements to cap and redistribute their excess across the rest, proportionally,
     * repeating until stable. A single element is forced to 1.0 (nothing to redistribute to).
     */
    internal fun capNormalize(weights: List<Double>, cap: Double): List<Double> {
        val total = weights.sum()
        if (weights.size == 1 || total <= 0.0) return List(weights.size) { 1.0 / weights.size }
        val w = weights.map { it / total }.toMutableList()
        repeat(weights.size) {
            val over = w.indices.filter { w[it] > cap + 1e-9 }
            if (over.isEmpty()) return w
            val excess = over.sumOf { w[it] - cap }
            over.forEach { w[it] = cap }
            val underSum = w.indices.filter { it !in over }.sumOf { w[it] }
            if (underSum <= 1e-12) return List(weights.size) { cap } // all capped — distribute evenly
            w.indices.filter { it !in over }.forEach { w[it] += excess * (w[it] / underSum) }
        }
        return w
    }
}
