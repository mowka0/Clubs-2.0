package com.clubs.bot

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventRepository
import com.clubs.event.EventService
import com.clubs.event.RosterService
import com.clubs.event.VoteService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Кнопки из DM организатору о недоборе состава (V83): напомнить · продлить · провести меньшим
 * составом · отменить. Разбирает callback-данные вида `roster:<действие>:<eventId>` и возвращает
 * текст всплывающего ответа Telegram — тот же контракт, что у
 * [com.clubs.chatlink.ChatLinkBotService.handleUnlinkCallback].
 *
 * Каждое действие проверяет право MANAGE_EVENTS в клубе события: пересланная кнопка из чужого DM
 * ничего не делает. Ошибки бизнес-правил (набор уже закрыт, продлевать некуда) возвращаются
 * человеку текстом, а не молча глотаются.
 */
@Service
class RosterBotService(
    private val eventRepository: EventRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val userRepository: UserRepository,
    private val rosterService: RosterService,
    private val eventService: EventService,
    private val voteService: VoteService
) {

    private val log = LoggerFactory.getLogger(RosterBotService::class.java)

    companion object {
        const val CALLBACK_PREFIX = "roster:"
        /** Причина отмены, которая уходит участникам в DM, когда набор так и не состоялся. */
        const val SHORTFALL_CANCEL_REASON = "Не набрался состав"
    }

    fun handleCallback(fromTelegramId: Long, data: String): String {
        val parts = data.removePrefix(CALLBACK_PREFIX).split(":")
        if (parts.size != 2) return "Некорректный запрос"
        val action = parts[0]
        val eventId = try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            return "Некорректный запрос"
        }

        val event = eventRepository.findById(eventId) ?: return "Встреча не найдена"
        val club = clubRepository.findById(event.clubId) ?: return "Клуб не найден"
        val callerId = userRepository.findByTelegramId(fromTelegramId)?.id ?: return "Профиль не найден"

        return try {
            clubRoleGuard.requireCapability(club, callerId, ClubCapability.MANAGE_EVENTS)
            when (action) {
                "remind" -> {
                    val reminded = voteService.remind(eventId, callerId, null).remindedCount
                    if (reminded > 0) "Напоминание отправлено: $reminded" else "Все уже получили напоминание"
                }
                "extend6" -> extend(event.id, 6)
                "extend12" -> extend(event.id, 12)
                "proceed" -> {
                    rosterService.proceedWithPartialRoster(event)
                    "Состав закрыт — встреча состоится"
                }
                "cancel" -> {
                    eventService.cancelEvent(eventId, callerId, SHORTFALL_CANCEL_REASON)
                    "Встреча отменена, участники предупреждены"
                }
                else -> {
                    log.warn("Unknown roster callback action: {}", action.take(16))
                    "Некорректный запрос"
                }
            }
        } catch (e: ForbiddenException) {
            "Управлять встречей может только организатор"
        } catch (e: ValidationException) {
            e.message ?: "Действие больше недоступно"
        }
    }

    /** Событие перечитывается: между отправкой DM и нажатием кнопки состояние набора могло уйти. */
    private fun extend(eventId: UUID, hours: Int): String {
        val fresh = eventRepository.findById(eventId) ?: return "Встреча не найдена"
        rosterService.extendRoster(fresh, hours)
        return "Набор продлён на $hours ч"
    }
}
