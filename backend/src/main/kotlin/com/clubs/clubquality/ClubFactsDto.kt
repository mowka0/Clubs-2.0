package com.clubs.clubquality

/**
 * HTTP shape of [ClubFacts] for `GET /api/clubs/{clubId}/quality`.
 *
 * @property meetingsPerMonth held events in the last 90 days ÷ 3 (Активность)
 * @property avgAttendance     average distinct attendees per finalized meeting, last 90 days (Приходит)
 * @property coreSize          distinct users with ≥3 attended events, all-time (Сплочённость / ядро)
 * @property ageMonths         full months since the club was created
 * @property totalMeetings     all-time held (past, non-cancelled) events (milestone «N встреч»)
 * @property successfulSkladchinas skladchinas closed as successful (milestone «первый сбор»)
 */
data class ClubFactsDto(
    val meetingsPerMonth: Double,
    val avgAttendance: Int,
    val coreSize: Int,
    val ageMonths: Int,
    val totalMeetings: Int,
    val successfulSkladchinas: Int,
)

/**
 * HTTP shape of [ClubCardFacts] for `GET /api/clubs/quality/batch?ids=...` — the Discovery feed
 * card's decision trio (возраст · участники · вовлечённость), one element per existing club.
 * «участники» comes from `ClubListItemDto.memberCount`, not here.
 *
 * @property clubId            the club these facts belong to (the caller keys the response by it)
 * @property ageDays           whole days since the club was created (возраст)
 * @property engagementPercent distinct recent responders ÷ alive members, 0..100 (вовлечённость)
 */
data class ClubCardFactsDto(
    val clubId: java.util.UUID,
    val ageDays: Int,
    val engagementPercent: Int,
)
