package com.clubs.bot

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.ProceedResult
import com.clubs.event.RemindResultDto
import com.clubs.event.RosterService
import com.clubs.event.VoteService
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Callback-кнопки набора (V86, § 4.1 спеки форматов). Права даёт не адресат DM, а тот же
 * сервисный метод, что у REST: чужой `from.id` получает «Нет прав», битый — «Некорректный запрос».
 */
class RosterCallbackServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val rosterService = mockk<RosterService>()
    private val voteService = mockk<VoteService>()
    private val service = RosterCallbackService(userRepository, rosterService, voteService)

    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun stubCaller() {
        // Конструктор jOOQ-записи приватный у KotlinGenerator — строим через mockk.
        every { userRepository.findByTelegramId(42L) } returns mockk<UsersRecord> { every { id } returns userId }
    }

    @Test
    fun `AC-6 «Проводим» — те же ответы, что у REST`() {
        stubCaller()
        every { rosterService.proceed(eventId, userId) } returns ProceedResult(3, alreadyDecided = false)
        assertEquals("Проводим составом 3", service.handleProceed(42L, eventId))

        every { rosterService.proceed(eventId, userId) } returns ProceedResult(3, alreadyDecided = true)
        assertEquals("Уже отмечено", service.handleProceed(42L, eventId))

        every { rosterService.proceed(eventId, userId) } throws ValidationException("Состав не ниже минимума — подтверждать нечего")
        assertEquals("Состав не ниже минимума — подтверждать нечего", service.handleProceed(42L, eventId))

        every { rosterService.proceed(eventId, userId) } throws NotFoundException("Event not found")
        assertEquals("Некорректный запрос", service.handleProceed(42L, eventId))
    }

    @Test
    fun `AC-6 чужой from_id — «Нет прав», незнакомый telegram id — «Некорректный запрос»`() {
        stubCaller()
        every { rosterService.proceed(eventId, userId) } throws ForbiddenException("no")
        assertEquals("Нет прав", service.handleProceed(42L, eventId))

        every { userRepository.findByTelegramId(99L) } returns null
        assertEquals("Некорректный запрос", service.handleProceed(99L, eventId))
        verify(exactly = 1) { rosterService.proceed(any(), any()) }
    }

    @Test
    fun `«Напомнить» — счётчик или «напоминать некому», права те же`() {
        stubCaller()
        every { voteService.remind(eventId, userId, null) } returns RemindResultDto(remindedCount = 2)
        assertEquals("Напомнили 2", service.handleRemind(42L, eventId))

        every { voteService.remind(eventId, userId, null) } returns RemindResultDto(remindedCount = 0)
        assertEquals("Напоминать некому", service.handleRemind(42L, eventId))

        every { voteService.remind(eventId, userId, null) } throws ValidationException("Event has already started")
        assertEquals("Напоминать некому", service.handleRemind(42L, eventId))

        every { voteService.remind(eventId, userId, null) } throws ForbiddenException("no")
        assertEquals("Нет прав", service.handleRemind(42L, eventId))
    }
}
