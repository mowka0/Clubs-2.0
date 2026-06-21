package com.clubs.clubquality

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Machine-enforces the boolean-only L3 contract (design §4 "L3 невидим и необъясним"): the internal
 * rank score / breakdown must NEVER appear in a serialized DTO. The `club_rank.rank_score` table sits
 * behind the public `/quality/batch` endpoint's repository, so only this test — not discipline — keeps
 * the gradient from leaking and being reverse-engineered.
 */
class ClubRankContractTest {

    private val mapper = jacksonObjectMapper()
    private val forbidden = listOf("rankScore", "rank_score", "effectiveK", "effective_k", "distinctCredible", "categoryRank")

    @Test
    fun `card DTO exposes only the boolean badge, never the score`() {
        val json = mapper.writeValueAsString(
            ClubCardFactsDto(clubId = UUID.randomUUID(), ageDays = 10, engagementPercent = 50, topInCategory = true),
        )
        @Suppress("UNCHECKED_CAST")
        val keys = (mapper.readValue(json, Map::class.java) as Map<String, Any?>).keys
        assertEquals(setOf("clubId", "ageDays", "engagementPercent", "topInCategory"), keys)
        forbidden.forEach { assertTrue(it !in json) { "L3 score leaked into ClubCardFactsDto: $it" } }
    }

    @Test
    fun `public facts DTO carries no L3 score`() {
        val json = mapper.writeValueAsString(
            ClubFactsDto(meetingsPerMonth = 1.0, avgAttendance = 4, coreSize = 5, ageMonths = 3, totalMeetings = 9, successfulSkladchinas = 1),
        )
        forbidden.forEach { assertTrue(it !in json) { "L3 score leaked into ClubFactsDto: $it" } }
    }
}
