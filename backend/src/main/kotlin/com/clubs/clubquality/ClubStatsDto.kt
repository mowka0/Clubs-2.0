package com.clubs.clubquality

/**
 * HTTP DTO for the owner-only club statistics panel — `GET /api/clubs/{clubId}/stats`.
 * Fields are nullable where the metric doesn't apply to this club (free clubs, no skladchinas,
 * non-`closed` access) so the frontend renders only the relevant levers (§9.3).
 */
data class ClubStatsDto(
    val clubType: String,
    val retentionPercent: Int?,
    val retentionTrend: TrendDto?,
    val churnedThisPeriod: Int,
    val rejoinedThisPeriod: Int,
    val engagementPercent: Int,
    val engagementTrend: TrendDto?,
    val skladchinaPaidPercent: Int?,
    val skladchinaPaidTrend: TrendDto?,
    val pendingApplications: Int?,
    val stalePendingApplications: Int?,
    val attendanceDisputes: Int,
    val totalMeetings: Int,
    val autoRejectedApplications: Int?,
    val cancelledMeetings: Int,
)

data class TrendDto(
    /** "up" | "down" | "flat". */
    val direction: String,
    /** Signed delta in percentage points (current window − prior window). */
    val delta: Int,
)
