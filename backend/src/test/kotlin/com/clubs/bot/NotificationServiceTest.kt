package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.EventStatus
import com.clubs.membership.MembershipRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Аудитория DM об ОТМЕНЕ события: все участники клуба с доступом (UPDATED 2026-07-05),
 * а не только going/maybe-голосовавшие.
 */
class NotificationServiceTest {

    private val membershipRepository = mockk<MembershipRepository>(relaxed = true)
    private val eventResponseRepository = mockk<EventResponseRepository>(relaxed = true)
    private val telegramClient = mockk<TelegramClient>(relaxed = true)
    private val service = NotificationService(
        membershipRepository, eventResponseRepository, telegramClient, "bot", "https://app"
    )

    @Test
    fun `sendEventCancelled notifies ALL club members with access, not just voters`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns listOf(101L, 102L, 103L)

        service.sendEventCancelled(event, reason = "погода")

        // аудитория = все участники клуба, НЕ подмножество going/maybe-голосовавших
        verify(exactly = 1) { membershipRepository.findMemberTelegramIds(event.clubId) }
        verify(exactly = 0) { eventResponseRepository.findStage2TargetTelegramIds(any()) }
        verify(exactly = 3) { telegramClient.execute(any<SendMessage>()) }
    }

    @Test
    fun `sendEventCancelled with no members sends nothing`() {
        val event = sampleEvent()
        every { membershipRepository.findMemberTelegramIds(event.clubId) } returns emptyList()

        service.sendEventCancelled(event, reason = null)

        verify(exactly = 0) { telegramClient.execute(any<SendMessage>()) }
    }

    private fun sampleEvent() = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Событие",
        description = null,
        locationText = "Бар",
        eventDatetime = OffsetDateTime.now().plusDays(3),
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = EventStatus.stage_2,
        stage2Triggered = true,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
