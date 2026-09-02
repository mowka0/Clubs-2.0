package com.clubs.bot

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.RosterService
import com.clubs.event.VoteService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Inline-кнопки набора состава в DM организатору (V86, § 4.1 спеки форматов): «Проводим» из
 * DM ③ и «Напомнить тем, кто не ответил» из DM ②.
 *
 * Права проверяет НЕ бот, а тот же сервисный метод, что обслуживает REST
 * (`ClubRoleGuard.requireCapability(MANAGE_EVENTS)`): `callback_data` подделываема, и угадав
 * `proceed:<eventId>`, чужой не должен ничего сделать. Адресат DM сам по себе прав не даёт —
 * `query.from.id` резолвится в пользователя и идёт через обычный гейт.
 *
 * Возвращает текст для `AnswerCallbackQuery` — бот показывает его алертом.
 */
@Service
class RosterCallbackService(
    private val userRepository: UserRepository,
    private val rosterService: RosterService,
    private val voteService: VoteService
) {

    private val log = LoggerFactory.getLogger(RosterCallbackService::class.java)

    companion object {
        const val PROCEED_CALLBACK_PREFIX = "roster:proceed:"
        const val REMIND_CALLBACK_PREFIX = "roster:remind:"
        // Тот же ответ, что у битого callback привязки чата: чужому не объясняем, что именно не так.
        const val INVALID_REQUEST = "Некорректный запрос"
    }

    fun handleProceed(fromTelegramId: Long, eventId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return INVALID_REQUEST
        return try {
            val result = rosterService.proceed(eventId, callerId)
            if (result.alreadyDecided) "Уже отмечено" else "Проводим составом ${result.confirmedCount}"
        } catch (e: ForbiddenException) {
            log.warn("Roster proceed callback denied: telegramId={} eventId={}", fromTelegramId, eventId)
            "Нет прав"
        } catch (e: ValidationException) {
            // Сообщения сервиса уже человеческие («Встреча отменена», «Состав не ниже минимума…»).
            e.message ?: INVALID_REQUEST
        } catch (e: NotFoundException) {
            INVALID_REQUEST
        }
    }

    fun handleRemind(fromTelegramId: Long, eventId: UUID): String {
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return INVALID_REQUEST
        return try {
            val reminded = voteService.remind(eventId, callerId, targetUserId = null).remindedCount
            if (reminded > 0) "Напомнили $reminded" else "Напоминать некому"
        } catch (e: ForbiddenException) {
            log.warn("Roster remind callback denied: telegramId={} eventId={}", fromTelegramId, eventId)
            "Нет прав"
        } catch (e: ValidationException) {
            // Набор уже закрыт отменой или встреча началась — напоминать больше не о чем.
            "Напоминать некому"
        } catch (e: NotFoundException) {
            INVALID_REQUEST
        }
    }
}
