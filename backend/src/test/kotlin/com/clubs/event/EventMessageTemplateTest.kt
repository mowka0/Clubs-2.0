package com.clubs.event

import com.clubs.generated.jooq.enums.EventStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Хвост DM о создании встречи: факты формата плюс срок «передумать без влияния на репутацию» (PO 2026-09-06). */
class EventMessageTemplateTest {

    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
    private val deadline = OffsetDateTime.of(2026, 9, 12, 19, 0, 0, 0, ZoneOffset.UTC)

    private fun event(limit: Int?, min: Int? = null) = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Баня",
        description = null,
        locationText = null,
        eventDatetime = deadline.plusHours(18),
        participantLimit = limit,
        minParticipants = min,
        votingOpensDaysBefore = 14,
        status = EventStatus.upcoming,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    @Test
    fun `dmFacts со сроком добавляет строку про репутацию`() {
        val text = EventMessageTemplate.dmFacts(event(limit = 6), deadline, fmt)
        assertEquals("👥 Мест — 6\n⏳ До 12.09.2026 19:00 МСК передумать можно без влияния на репутацию.", text)
    }

    @Test
    fun `dmFacts без срока — только факт формата`() {
        assertEquals("👥 Мест — 6", EventMessageTemplate.dmFacts(event(limit = 6)))
        assertTrue(!EventMessageTemplate.dmFacts(event(limit = null)).contains("передумать"))
    }
}
