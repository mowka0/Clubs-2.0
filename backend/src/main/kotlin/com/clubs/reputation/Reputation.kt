package com.clubs.reputation

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Кэшированный агрегат репутации по клубу, производный (пересчитываемый) от reputation_ledger.
 * `reliabilityIndex` всегда — ИСТИННАЯ Σ баллов (NOT NULL) — порог отображения "Новичок"
 * (ReputationPolicy.isShown(outcomeCount)) применяется на границе DTO, никогда не здесь.
 */
data class Reputation(
    val userId: UUID,
    val clubId: UUID,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val spontaneityCount: Int,
    val outcomeCount: Int,
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Кросс-клубовый агрегат строк репутации одного пользователя.
 * memberClubCount = количество клубов, в которых у пользователя есть строка репутации
 * (т.е. клубы с историей; владельцы не накапливают репутацию в своём клубе по правилу
 * анти-фарма №1). totalConfirmations / totalAttendances = SUM по этим строкам.
 */
data class PeerStatsAggregate(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int
) {
    companion object {
        val EMPTY = PeerStatsAggregate(0, 0, 0)
    }
}
