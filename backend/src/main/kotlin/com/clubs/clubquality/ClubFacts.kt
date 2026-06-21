package com.clubs.clubquality

import java.util.UUID

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
    /** All-time held (past, non-cancelled) events — feeds the «N встреч» milestone. */
    val totalMeetings: Int,
    /** Skladchinas closed as successful — feeds the «первый сбор» milestone. */
    val successfulSkladchinas: Int,
)

/**
 * Lean per-club facts for the Discovery feed card («листать или зайти?», §11.1), computed in BATCH
 * over a page of clubs (no N+1). The card's decision trio is **возраст · участники · вовлечённость**:
 * deliberately NOT встреч/мес or ядро — those are the club page's own rings (Активность / Сплочённость),
 * so the card stays minimal and non-duplicating. «участники» isn't here either — the card already has
 * `ClubListItemDto.memberCount`.
 *
 * Design contract: docs/backlog/club-quality-gamification.md §11.1, §11.4 ("now" metrics).
 */
data class ClubCardFacts(
    val clubId: UUID,
    /** Whole days since the club was created (возраст). */
    val ageDays: Int,
    /** Distinct members who responded to events in the last 90 days ÷ alive members, 0..100 (вовлечённость). */
    val engagementPercent: Int,
)
