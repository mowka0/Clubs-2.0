package com.clubs.interest

import java.util.UUID

interface InterestRepository {

    /** Names whose canonical form starts with [prefix], most-used first. */
    fun suggest(prefix: String, limit: Int): List<String>

    /** Insert any missing names (ignore conflicts) and return name → id for all. */
    fun upsertAll(names: List<String>): Map<String, UUID>

    fun findUserInterestIds(userId: UUID): Set<UUID>
    fun findUserInterestNames(userId: UUID): List<String>

    fun linkUserInterests(userId: UUID, interestIds: Collection<UUID>)
    fun unlinkUserInterests(userId: UUID, interestIds: Collection<UUID>)

    /** Adjust popularity counters (delta clamped at 0 on the floor). */
    fun adjustUsage(interestIds: Collection<UUID>, delta: Int)
}
