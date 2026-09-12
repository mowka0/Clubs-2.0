package com.clubs.bot

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.debt.DebtService
import com.clubs.debt.DebtSettlementService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Inline-кнопки «Получил / Не получил» в DM получателю (skladchina-v3 § 5): по долгу и по сальдо
 * пары. Права проверяет НЕ бот, а тот же сервис, что обслуживает REST: `callback_data`
 * подделываема, и угадав `debt:confirm:<id>`, чужой не должен ничего сделать —
 * `query.from.id` резолвится в пользователя и идёт через обычную проверку стороны долга.
 *
 * Возвращает текст для `AnswerCallbackQuery` — бот показывает его алертом.
 */
@Service
class DebtCallbackService(
    private val userRepository: UserRepository,
    private val debtService: DebtService,
    private val settlementService: DebtSettlementService
) {
    private val log = LoggerFactory.getLogger(DebtCallbackService::class.java)

    companion object {
        const val CONFIRM_PREFIX = "debt:confirm:"
        const val REJECT_PREFIX = "debt:reject:"
        const val SETTLE_CONFIRM_PREFIX = "settle:confirm:"
        const val SETTLE_REJECT_PREFIX = "settle:reject:"
    }

    fun handleDebt(fromTelegramId: Long, debtId: UUID, confirm: Boolean): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, debtId) {
            if (confirm) {
                debtService.confirm(debtId, callerId)
                "Получено ✅"
            } else {
                debtService.reject(debtId, callerId, note = null)
                "Отмечено: не получил"
            }
        }
    }

    fun handleSettlement(fromTelegramId: Long, settlementId: UUID, confirm: Boolean): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, settlementId) {
            if (confirm) {
                settlementService.confirm(settlementId, callerId)
                "Сальдо закрыто ✅"
            } else {
                settlementService.reject(settlementId, callerId)
                "Отмечено: не получил"
            }
        }
    }

    private fun guarded(fromTelegramId: Long, id: UUID, action: () -> String): String = try {
        action()
    } catch (e: ForbiddenException) {
        log.warn("Debt callback denied: telegramId={} id={}", fromTelegramId, id)
        "Нет прав"
    } catch (e: ValidationException) {
        // Сообщения сервиса уже человеческие («Для этого долга такое действие недоступно»).
        e.message ?: RosterCallbackService.INVALID_REQUEST
    } catch (e: ConflictException) {
        "Уже отвечено"
    } catch (e: NotFoundException) {
        RosterCallbackService.INVALID_REQUEST
    }
}
