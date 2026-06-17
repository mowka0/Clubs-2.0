package com.clubs.clubquality

/**
 * L1 facts about a club ("place" subject, anchored on club_id) — derived read-only from
 * existing tables, no own schema. These are the honest, publicly-visible signals a chooser
 * needs ("is this club alive, worth joining?"). Not a score, not member-Trust average.
 *
 * Design contract: docs/backlog/club-quality-gamification.md §1–3, §11.4 ("now" metrics).
 */
data class ClubFacts(
    val meetingsPerMonth: Double,
    val avgAttendance: Int,
    val coreSize: Int,
    val ageMonths: Int,
)
