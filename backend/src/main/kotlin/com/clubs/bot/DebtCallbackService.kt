package com.clubs.bot

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.common.util.Money
import com.clubs.debt.DebtService
import com.clubs.debt.DebtSettlementService
import com.clubs.skladchina.SkladchinaLifecycleService
import com.clubs.skladchina.SkladchinaParticipationService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Inline-кнопки в DM (skladchina-v3 § 5): «Получил / Не получил» по долгу и по сальдо пары,
 * «В деле» на этапе записи, «Беру» в «Кто берёт?», «Простить» при −40, «Закрыть сбор» у «По желанию». Права проверяет НЕ бот, а тот же сервис, что обслуживает REST: `callback_data`
 * подделываема, и угадав `debt:confirm:<id>`, чужой не должен ничего сделать —
 * `query.from.id` резолвится в пользователя и идёт через обычную проверку стороны долга.
 *
 * Возвращает текст для `AnswerCallbackQuery` — бот показывает его алертом.
 */
@Service
class DebtCallbackService(
    private val userRepository: UserRepository,
    private val debtService: DebtService,
    private val settlementService: DebtSettlementService,
    private val participationService: SkladchinaParticipationService,
    private val lifecycleService: SkladchinaLifecycleService
) {
    private val log = LoggerFactory.getLogger(DebtCallbackService::class.java)

    companion object {
        const val CONFIRM_PREFIX = "debt:confirm:"
        const val REJECT_PREFIX = "debt:reject:"
        const val SETTLE_CONFIRM_PREFIX = "settle:confirm:"
        const val SETTLE_REJECT_PREFIX = "settle:reject:"
        const val ENROLL_PREFIX = "skladchina:enroll:"
        const val TAKE_PREFIX = "skladchina:take:"
        const val FORGIVE_PREFIX = "debt:forgive:"
        const val CLOSE_PREFIX = "skladchina:close:"
    }

    /** «Простить» из DM о −40 — то же прощение, что в приложении, права по from.id. */
    fun handleForgive(fromTelegramId: Long, debtId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, debtId) {
            debtService.forgive(debtId, callerId)
            "Долг прощён"
        }
    }

    /** «Закрыть сбор» из DM «срок прошёл» («По желанию»): неразобранные переводы вернут человеческую ошибку. */
    fun handleClose(fromTelegramId: Long, skladchinaId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, skladchinaId) {
            lifecycleService.close(skladchinaId, callerId)
            "Сбор закрыт ✅"
        }
    }

    /** «Беру» из DM о сборе «Кто берёт?»: одна штука, без заметки — как кнопка в приложении по умолчанию. */
    fun handleTake(fromTelegramId: Long, skladchinaId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, skladchinaId) {
            val detail = participationService.join(skladchinaId, callerId, note = null, quantity = 1)
            "Записали за вами: ${Money.rub(detail.myDebt?.amountKopecks ?: detail.amountKopecks ?: 0L)}. Берут ${detail.debtCount}."
        }
    }

    /** «В деле» из DM о сборе с этапом записи — тот же join, что у кнопки в приложении. */
    fun handleEnroll(fromTelegramId: Long, skladchinaId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return RosterCallbackService.INVALID_REQUEST
        return guarded(fromTelegramId, skladchinaId) {
            val detail = participationService.join(skladchinaId, callerId, note = null)
            "Вы в деле! Отметились ${detail.enrolledCount}" + (detail.minParticipants?.let { ", нужно $it" } ?: "") + "."
        }
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
