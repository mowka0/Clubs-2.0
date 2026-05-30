package com.clubs.interest

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InterestService(private val interestRepository: InterestRepository) {

    private val log = LoggerFactory.getLogger(InterestService::class.java)

    @Transactional(readOnly = true)
    fun suggest(rawQuery: String, limit: Int): List<String> {
        val query = InterestNormalizer.normalize(rawQuery) ?: return emptyList()
        if (query.length < InterestNormalizer.MIN_QUERY_LEN) return emptyList()
        return interestRepository.suggest(query, limit.coerceIn(1, MAX_SUGGEST))
    }

    @Transactional(readOnly = true)
    fun getUserInterests(userId: UUID): List<String> =
        interestRepository.findUserInterestNames(userId)

    /**
     * Replaces the user's interests with [rawNames] (normalized + deduped).
     * Diffs against current links so popularity counters only move for genuine
     * adds/removes. Runs in the caller's transaction (profile update).
     */
    @Transactional
    fun replaceUserInterests(userId: UUID, rawNames: List<String>) {
        val normalized = InterestNormalizer.normalizeList(rawNames)
        val nameToId = interestRepository.upsertAll(normalized)
        val newIds = normalized.mapNotNull { nameToId[it] }.toSet()
        val currentIds = interestRepository.findUserInterestIds(userId)

        val toRemove = currentIds - newIds
        val toAdd = newIds - currentIds
        if (toRemove.isNotEmpty()) {
            interestRepository.unlinkUserInterests(userId, toRemove)
            interestRepository.adjustUsage(toRemove, -1)
        }
        if (toAdd.isNotEmpty()) {
            interestRepository.linkUserInterests(userId, toAdd)
            interestRepository.adjustUsage(toAdd, +1)
        }
        log.info("Interests updated: userId={} count={} added={} removed={}",
            userId, newIds.size, toAdd.size, toRemove.size)
    }

    companion object {
        private const val MAX_SUGGEST = 10
    }
}
