package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.Event
import com.clubs.event.EventCreatedEvent
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Closes the listener-wiring half of the "event-created DM never fires" regression:
 * the @TransactionalEventListener must forward the created Event to
 * NotificationService.sendEventCreated. (EventService publishing the event is
 * covered by EventServiceTest; AFTER_COMMIT dispatch is a Spring guarantee.)
 *
 * Маршрутизатор (PO 2026-07-08): листенер — оркестратор «сначала чат-пост, потом DM»,
 * chatId фактически вышедшего поста прокидывается в sendEventCreated для DM-фоллбека.
 */
class EventBotNotifierTest {

    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val livePinService = mockk<LivePinService>(relaxed = true)
    private val notifier = EventBotNotifier(notificationService, livePinService)

    @Test
    fun `onEventCreated постит живой закреп и передаёт его chatId в sendEventCreated`() {
        val event = sampleEvent()
        every { livePinService.onEventCreated(event) } returns -100123L

        notifier.onEventCreated(EventCreatedEvent(event))

        verify(exactly = 1) { notificationService.sendEventCreated(event, -100123L) }
    }

    @Test
    fun `onEventCreated без чат-поста — sendEventCreated с null (DM всем)`() {
        val event = sampleEvent()
        every { livePinService.onEventCreated(event) } returns null

        notifier.onEventCreated(EventCreatedEvent(event))

        verify(exactly = 1) { notificationService.sendEventCreated(event, null) }
    }

    private fun sampleEvent() = Event(
        id = UUID.randomUUID(),
        clubId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        title = "Test event",
        description = null,
        locationText = "Bar 1",
        eventDatetime = OffsetDateTime.now().plusDays(7),
        participantLimit = 20,
        votingOpensDaysBefore = 14,
        status = EventStatus.upcoming,
        stage2Triggered = false,
        attendanceMarked = false,
        attendanceFinalized = false,
        photoUrl = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )
}
