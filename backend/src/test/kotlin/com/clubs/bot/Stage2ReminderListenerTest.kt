package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.Stage2ReminderSentEvent
import com.clubs.generated.jooq.enums.EventStatus
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Листенер — единственный мост между ручным напоминанием менеджера и DM участнику: без этой
 * проводки sendStage2Reminder был бы мёртвым кодом. Автоматического DM «Этап 2 начался» больше
 * нет — второй этап не начинается ни у одной новой встречи (event-formats.md § 16.5).
 */
class Stage2ReminderListenerTest {

    private val notificationService = mockk<NotificationService>()
    private val listener = Stage2ReminderListener(notificationService)

    @Test
    fun `forwards the manual reminder to sendStage2Reminder`() {
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
            status = EventStatus.upcoming,
            stage2Triggered = false,
            attendanceMarked = false,
            attendanceFinalized = false,
            photoUrl = null,
            createdAt = null,
            updatedAt = null
        )
        val deadline = event.eventDatetime.minusHours(18)
        val telegramIds = listOf(1L, 2L)
        justRun { notificationService.sendStage2Reminder(event, telegramIds, deadline) }

        listener.onStage2ReminderSent(Stage2ReminderSentEvent(event, telegramIds, deadline))

        verify(exactly = 1) { notificationService.sendStage2Reminder(event, telegramIds, deadline) }
    }
}
