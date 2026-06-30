package com.clubs.award

import java.util.UUID

interface AwardRepository {

    /** All awards granted to [userId] in [clubId], newest first. Visible to all members (R3). */
    fun findByMember(clubId: UUID, userId: UUID): List<Award>

    /** Every award in [clubId] (newest first), for the roster — the service groups them per member. */
    fun findByClub(clubId: UUID): List<Award>

    /** Distinct (emoji, label) ever granted in [clubId], most-used first — the grant-form autocomplete. */
    fun findSuggestions(clubId: UUID, limit: Int): List<AwardSuggestion>

    /** How many awards [userId] already holds in [clubId] (enforces the per-member cap). */
    fun countByMember(clubId: UUID, userId: UUID): Int

    /** Whether [userId] already holds an award with this exact [label] in [clubId] (no duplicates). */
    fun existsByLabel(clubId: UUID, userId: UUID, label: String): Boolean

    fun insert(award: Award): Award

    /** Deletes the award only if it belongs to (clubId, userId). Returns rows affected (0 = not found). */
    fun delete(awardId: UUID, clubId: UUID, userId: UUID): Int
}
