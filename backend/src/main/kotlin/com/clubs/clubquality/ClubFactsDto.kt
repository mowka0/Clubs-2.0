package com.clubs.clubquality

/**
 * HTTP shape of [ClubFacts] for `GET /api/clubs/{clubId}/quality`.
 *
 * @property meetingsPerMonth held events in the last 90 days ÷ 3 (Активность)
 * @property avgAttendance     average distinct attendees per finalized meeting, last 90 days (Приходит)
 * @property coreSize          distinct users with ≥3 attended events, all-time (Сплочённость / ядро)
 * @property ageMonths         full months since the club was created
 */
data class ClubFactsDto(
    val meetingsPerMonth: Double,
    val avgAttendance: Int,
    val coreSize: Int,
    val ageMonths: Int,
)
