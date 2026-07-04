package com.clubs.skladchina.template

import java.util.UUID

/**
 * Равный сплит общей суммы между участниками. Остаток (копейки, которые не делятся нацело)
 * достаётся ПОСЛЕДНЕМУ участнику в порядке сортировки по userId — детерминированно, чтобы тесты
 * и повторные прогоны сходились. Общий для всех шаблонов, делящих фиксированную сумму
 * (custom fixed_equal, split_bill).
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
