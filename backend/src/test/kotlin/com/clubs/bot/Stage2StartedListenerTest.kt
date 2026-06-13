package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.Stage2StartedEvent
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * S2T-2: the listener is the only bridge between the Stage 2 transition and the confirm DM —
 * before this wiring existed, sendStage2Started was dead code and the two-stage flow starved.
 */
class Stage2StartedListenerTest {

    private val notificationService = mockk<NotificationService>()
    private val listener = Stage2StartedListener(notificationService)

    @Test
    fun `forwards the event to sendStage2Started`() {
        val event = Event(
            id = UUID.randomUUID(),
            clubId = UUID.randomUUID(),
            createdBy = UUID.randomUUID(),
            title = "Event",
            description = null,
            locationText = "Place",
            eventDatetime = OffsetDateTime.now().plusDays(1),
            participantLimit = 10,
            votingOpensDaysBefore = 14,
            status = EventStatus.stage_2,
            stage2Triggered = true,
            attendanceMarked = false,
            attendanceFinalized = false,
            photoUrl = null,
            createdAt = null,
            updatedAt = null
        )
        justRun { notificationService.sendStage2Started(event) }

        listener.onStage2Started(Stage2StartedEvent(event))

        verify(exactly = 1) { notificationService.sendStage2Started(event) }
    }
}
