package com.clubs.skladchina

import java.util.UUID

/**
 * Равный сплит общей суммы между людьми. Остаток копеек, который не делится нацело, достаётся
 * ПОСЛЕДНЕМУ по сортировке userId — детерминированно, чтобы тесты и повторные прогоны сходились.
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
