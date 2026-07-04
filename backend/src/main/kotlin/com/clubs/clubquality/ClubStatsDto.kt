package com.clubs.clubquality

/**
 * HTTP DTO для панели статистики клуба только для владельца — `GET /api/clubs/{clubId}/stats`.
 * Поля nullable там, где метрика не применима к этому клубу (бесплатные клубы, нет складчин,
 * доступ не `closed`), чтобы фронтенд рендерил только релевантные рычаги (§9.3).
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
    /** Дельта со знаком в процентных пунктах (текущее окно − предыдущее окно). */
    val delta: Int,
)
