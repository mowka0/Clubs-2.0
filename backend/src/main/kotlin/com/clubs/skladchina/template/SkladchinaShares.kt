package com.clubs.skladchina.template

import java.util.UUID

/**
 * Equal split of a total across participants. Remainder (kopecks that don't divide evenly)
 * goes to the LAST participant in sorted-by-userId order — deterministic, so tests and reruns
 * agree. Shared by every template that splits a fixed total (custom fixed_equal, split_bill).
 */
object SkladchinaShares {
    fun equal(totalKopecks: Long, userIds: List<UUID>): List<Pair<UUID, Long>> {
        require(userIds.isNotEmpty()) { "userIds must not be empty" }
        val n = userIds.size
        val base = totalKopecks / n
        val remainder = totalKopecks - base * n
        return userIds.sortedBy { it }.mapIndexed { idx, userId ->
            userId to if (idx == n - 1) base + remainder else base
        }
    }
}
