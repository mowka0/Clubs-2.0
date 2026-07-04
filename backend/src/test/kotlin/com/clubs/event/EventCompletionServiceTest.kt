package com.clubs.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Unit-тест точки входа шедулера. Проверяет, что cutoff считается как
 * `now - COMPLETION_GRACE_HOURS` и делегируется в репозиторий. Фактическая семантика
 * jOOQ-запроса (какие статусы/даты обновляются) покрыта в
 * [EventCompletionRepositoryTest] на реальном Postgres.
 */
class EventCompletionServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var service: EventCompletionService

    @BeforeEach
    fun setUp() {
        eventRepository = mockk(relaxed = true)
        service = EventCompletionService(eventRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(OffsetDateTime::class)
    }

    @Test
    fun `completePastEvents calls repository with cutoff 6 hours before now`() {
        val fixedNow = OffsetDateTime.parse("2026-05-24T12:00:00Z")
        mockkStatic(OffsetDateTime::class)
        every { OffsetDateTime.now() } returns fixedNow
        every { eventRepository.markPastEventsCompleted(any()) } returns 0

        service.completePastEvents()

        verify(exactly = 1) {
            eventRepository.markPastEventsCompleted(fixedNow.minusHours(6))
        }
    }

    @Test
    fun `completePastEvents delegates regardless of how many rows were updated`() {
        every { eventRepository.markPastEventsCompleted(any()) } returns 5

        service.completePastEvents()

        verify(exactly = 1) { eventRepository.markPastEventsCompleted(any()) }
    }
}
