package com.clubs.bot

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.debt.DebtService
import com.clubs.debt.DebtSettlementService
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Кнопки «Получил / Не получил» в DM (skladchina-v3 § 5). `callback_data` подделываема: права
 * даёт не адресат DM, а тот же сервисный метод, что у REST — чужой `from.id` получает «Нет прав».
 */
class DebtCallbackServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val debtService = mockk<DebtService>()
    private val settlementService = mockk<DebtSettlementService>()
    private val service = DebtCallbackService(userRepository, debtService, settlementService)

    private val debtId = UUID.randomUUID()
    private val settlementId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun stubCaller() {
        every { userRepository.findByTelegramId(42L) } returns mockk<UsersRecord> { every { id } returns userId }
    }

    @Test
    fun `confirm and reject by debt go through DebtService with the caller resolved from telegram id`() {
        stubCaller()
        every { debtService.confirm(debtId, userId) } returns mockk()
        assertEquals("Получено ✅", service.handleDebt(42L, debtId, confirm = true))
        every { debtService.reject(debtId, userId, null) } returns mockk()
        assertEquals("Отмечено: не получил", service.handleDebt(42L, debtId, confirm = false))
        verify(exactly = 1) { debtService.confirm(debtId, userId) }
        verify(exactly = 1) { debtService.reject(debtId, userId, null) }
    }

    @Test
    fun `forged callback from someone else is «Нет прав», unknown telegram id never reaches the service`() {
        stubCaller()
        every { debtService.confirm(debtId, userId) } throws ForbiddenException("Это действие доступно только получателю")
        assertEquals("Нет прав", service.handleDebt(42L, debtId, confirm = true))

        every { userRepository.findByTelegramId(99L) } returns null
        assertEquals(RosterCallbackService.INVALID_REQUEST, service.handleDebt(99L, debtId, confirm = true))
        verify(exactly = 1) { debtService.confirm(any(), any()) }
    }

    @Test
    fun `second tap, wrong status and missing debt map to human alerts`() {
        stubCaller()
        every { debtService.confirm(debtId, userId) } throws ConflictException("уже")
        assertEquals("Уже отвечено", service.handleDebt(42L, debtId, confirm = true))
        every { debtService.confirm(debtId, userId) } throws ValidationException("Для этого долга такое действие недоступно")
        assertEquals("Для этого долга такое действие недоступно", service.handleDebt(42L, debtId, confirm = true))
        every { debtService.confirm(debtId, userId) } throws NotFoundException("Долг не найден")
        assertEquals(RosterCallbackService.INVALID_REQUEST, service.handleDebt(42L, debtId, confirm = true))
    }

    @Test
    fun `settlement buttons go through DebtSettlementService and keep the same guards`() {
        stubCaller()
        every { settlementService.confirm(settlementId, userId) } returns mockk()
        assertEquals("Сальдо закрыто ✅", service.handleSettlement(42L, settlementId, confirm = true))
        every { settlementService.reject(settlementId, userId) } throws ForbiddenException("Подтверждает получатель по сальдо")
        assertEquals("Нет прав", service.handleSettlement(42L, settlementId, confirm = false))
    }
}
