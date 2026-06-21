package com.clubs.clubquality

/**
 * Owner-only club statistics (subject = place, anchor = club_id) — the private «Статистика» panel
 * (§9 of docs/modules/club-quality.md). L1/L2 layer: counted generously, owner data allowed; the
 * anti-farm protections (distinct/absolutes/decay/min-K) belong to the hidden L3 rank, NOT here.
 *
 * Read-only aggregations over `transactions`, `membership_history`, `applications`, `event_responses`,
 * `events`, `skladchina_participants`, `skladchinas`, `memberships`, `clubs`. The reputation ledger is
 * NOT read (rule §2): «споры по явке» are counted straight from `event_responses` (current open
 * disputes), not from the ledger.
 *
 * Design: docs/backlog/club-quality-gamification.md §11.3–§11.4 + final.html block 3.
 */
data class ClubStats(
    val clubType: ClubType,
    // ---- Рычаги роста (growth levers) ----
    /** Paid renewal rate over 30 days (renewals ÷ renewals+churn), 0..100. Null for free clubs / no data. */
    val retentionPercent: Int?,
    val retentionTrend: Trend?,
    /** Members who lapsed (paid: left+expired) / left (free) in the last 30 days. */
    val churnedThisPeriod: Int,
    /** Members who came back (membership_history `rejoined`) in the last 30 days. Rendered for free clubs. */
    val rejoinedThisPeriod: Int,
    /** Distinct responders ÷ alive members over 90 days, 0..100 (same definition as the Discovery card). */
    val engagementPercent: Int,
    val engagementTrend: Trend?,
    /** Paid share among settled skladchina participants over 90 days, 0..100. Null if no closed skladchinas. */
    val skladchinaPaidPercent: Int?,
    val skladchinaPaidTrend: Trend?,
    /** Pending applications awaiting a decision. Null if the club doesn't accept applications (not closed). */
    val pendingApplications: Int?,
    /** Subset of [pendingApplications] older than 24h (approaching the 48h auto-reject). Null when not closed. */
    val stalePendingApplications: Int?,
    // ---- Зона внимания (owner-only «Надёжность организатора» negatives) ----
    /** Attendance disputes ever raised against the club's marks (cumulative — open + resolved). */
    val attendanceDisputes: Int,
    /** All-time held meetings — denominator context for disputes («N из M»). */
    val totalMeetings: Int,
    /** Applications auto-rejected in the last 90 days. Null if the club doesn't accept applications. */
    val autoRejectedApplications: Int?,
    /** Events cancelled in the last 90 days. */
    val cancelledMeetings: Int,
)

enum class ClubType { paid, free }

enum class TrendDirection { up, down, flat }

/** Window-over-window movement of a percent metric. [delta] is signed, in percentage points. */
data class Trend(val direction: TrendDirection, val delta: Int)
