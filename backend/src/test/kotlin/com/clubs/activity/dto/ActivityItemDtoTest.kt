package com.clubs.activity.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip serialization tests for [ActivityItemDto].
 *
 * Guards the explicit-discriminator design: removing `@JsonTypeInfo` means
 * Jackson must serialize the `type` field as a regular property — exactly once,
 * with the constant value declared on the concrete subtype.
 */
class ActivityItemDtoTest {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @Test
    fun `EventActivity serializes with exactly one type field equal to event`() {
        val dto = ActivityItemDto.EventActivity(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            clubId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            title = "Coffee meetup",
            createdAt = OffsetDateTime.parse("2026-05-01T10:00:00Z"),
            isCompleted = false,
            eventDatetime = OffsetDateTime.parse("2026-06-01T18:00:00Z"),
            locationText = "Main square",
            participantLimit = 20,
            goingCount = 5,
            status = "PUBLISHED",
            descriptionPreview = "Bring your laptop"
        )

        val json = mapper.writeValueAsString(dto)

        assertEquals(1, countOccurrences(json, "\"type\""), "exactly one type field expected; got: $json")
        assertTrue(json.contains("\"type\":\"event\""), "expected type=event; got: $json")
        assertTrue(json.contains("\"locationText\":\"Main square\""))
        assertTrue(json.contains("\"participantLimit\":20"))
        assertTrue(json.contains("\"goingCount\":5"))
        assertTrue(json.contains("\"status\":\"PUBLISHED\""))
        assertTrue(json.contains("\"descriptionPreview\":\"Bring your laptop\""))
    }

    @Test
    fun `SkladchinaActivity serializes with exactly one type field equal to skladchina`() {
        val dto = ActivityItemDto.SkladchinaActivity(
            id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            clubId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            title = "Pizza night",
            createdAt = OffsetDateTime.parse("2026-05-02T12:00:00Z"),
            isCompleted = false,
            paymentMode = "FIXED",
            totalGoalKopecks = 500000L,
            collectedKopecks = 120000L,
            deadline = OffsetDateTime.parse("2026-05-20T23:59:59Z"),
            participantCount = 10,
            paidCount = 3,
            status = "ACTIVE",
            affectsReputation = true
        )

        val json = mapper.writeValueAsString(dto)

        assertEquals(1, countOccurrences(json, "\"type\""), "exactly one type field expected; got: $json")
        assertTrue(json.contains("\"type\":\"skladchina\""), "expected type=skladchina; got: $json")
        assertTrue(json.contains("\"paymentMode\":\"FIXED\""))
        assertTrue(json.contains("\"totalGoalKopecks\":500000"))
        assertTrue(json.contains("\"collectedKopecks\":120000"))
        assertTrue(json.contains("\"participantCount\":10"))
        assertTrue(json.contains("\"paidCount\":3"))
        assertTrue(json.contains("\"status\":\"ACTIVE\""))
        assertTrue(json.contains("\"affectsReputation\":true"))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val found = haystack.indexOf(needle, index)
            if (found < 0) return count
            count++
            index = found + needle.length
        }
    }
}
