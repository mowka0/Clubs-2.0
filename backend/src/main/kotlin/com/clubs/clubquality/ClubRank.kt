package com.clubs.clubquality

import java.time.OffsetDateTime
import java.util.UUID

/**
 * One credible account's contribution to a distinct-account axis: the user and the behaviour time
 * that anchors recency-decay (latest qualifying event / completed payment / vote). Absolutes only —
 * we count distinct accounts, never raw events or ratios (anti-farm §3.4).
 */
data class AccountOutcome(val userId: UUID, val occurredAt: OffsetDateTime)

/**
 * Per-account inputs to the L3 credibility weight, read once per recompute (not per club). All four
 * inputs are owner-resistant: account age, profile completeness, and the cross-OWNER ledger footprint
 * (distinct independent owners where the account earned a kept promise — the Sybil-tax that makes a
 * sock-puppet expensive). [footprintByOwner] maps `ownerId → distinct kept-outcome clubs`.
 */
data class CredibilityInput(
    val userId: UUID,
    val createdAt: OffsetDateTime,
    val hasUsername: Boolean,
    val hasAvatar: Boolean,
    val footprintByOwner: Map<UUID, Int>,
)

/**
 * Raw, per-club signals the repository gathers for one club. The policy turns these into a score; the
 * repository does NO scoring. Every distinct-account list is already filtered to be member-driven and
 * owner-excluded at the query (e.g. [core] requires a member's stage-1 vote before the event, so an
 * owner-marked attendance alone cannot qualify an account). [category] is the enum's name (kept as a
 * plain String so the policy stays jOOQ-free and unit-testable).
 */
data class ClubRankSignals(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val isPaid: Boolean,
    val clubCreatedAt: OffsetDateTime,
    /** Distinct core members: ≥2 attended on different events, member-vote-before-event, spread ≥7d (§3.1). */
    val core: List<AccountOutcome>,
    /** Distinct Stars payers from `transactions.completed` (§3.2). Empty for free clubs. */
    val payers: List<AccountOutcome>,
    /** Payers who left ≤14d after paying — scam signature, contribution halved (§3.2). */
    val scamPayers: Set<UUID>,
    /** Distinct accounts that renewed (`transactions.renewal`) — an absolute, NOT a ratio (§3.2). */
    val renewers: List<AccountOutcome>,
    /** Distinct member stage-1 voters on non-cancelled events, 90d window (§3.3). */
    val voters: List<AccountOutcome>,
    /** Behaviour times of events with ≥4 distinct qualifying attended — the LiveActivity volume (§3.4). */
    val qualityEvents: List<OffsetDateTime>,
    /** Dispute behaviour times (event datetimes), identity NOT read (§4). */
    val disputes: List<OffsetDateTime>,
    /** Organizer ghosting (finalized ∧ ¬marked) behaviour times (§4). */
    val ghosting: List<OffsetDateTime>,
    /** Auto-rejected applications' resolved times (closed clubs only) (§4). */
    val autoRejects: List<OffsetDateTime>,
    /** Skladchina `expired_no_response` closed times (§4). */
    val skladchinaGhosts: List<OffsetDateTime>,
    /** `left`+`expired` membership events in the 90d window — anomaly "too clean" input (§4). */
    val churnEvents90d: Int,
)

/**
 * The stored L3 outcome for one club. INTERNAL — [rankScore]/[effectiveK] never leave the server.
 * The only externally-visible derivative is the boolean "★ Топ-5 в категории".
 */
data class ClubRank(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val rankScore: Double,
    val isRanked: Boolean,
    val effectiveK: Double,
)

/** A ranked club as the category leaderboard sees it — input to the "★ Топ-5" badge computation. */
data class RankedClub(
    val clubId: UUID,
    val ownerId: UUID,
    val category: String,
    val rankScore: Double,
)
