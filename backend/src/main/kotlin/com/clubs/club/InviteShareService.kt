package com.clubs.club

import com.clubs.bot.NotificationService
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Личные приглашения в клуб (club-invites, кадры A–C мокапа): собирает данные для боттом-шита
 * «Пригласить» — deep-link на посадочную `/invite/<code>` и prepared message для нативного
 * пикера Telegram (сообщение уходит от имени организатора, не от бота — бот не может писать
 * незнакомцам и не видит контакты).
 */
@Service
class InviteShareService(
    private val clubService: ClubService,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    // Username бота — основа deep-link'а t.me/<bot>?startapp=…
    @Value("\${telegram.bot-username}") private val botUsername: String
) {

    private val log = LoggerFactory.getLogger(InviteShareService::class.java)

    fun createShare(clubId: UUID, callerId: UUID): InviteShareDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        requireOwnerOrOrganizer(club, callerId)

        val code = clubService.ensureInviteCode(clubId)
        val inviteUrl = "https://t.me/$botUsername?startapp=invite_$code"

        val caller = userRepository.findById(callerId) ?: throw NotFoundException("User not found")
        val sharerName = listOfNotNull(caller.firstName, caller.lastName).joinToString(" ").ifBlank { "Организатор" }

        val messageHtml = buildString {
            append("<b>").append(escapeHtml(sharerName)).append("</b>")
            append(" приглашает вас в клуб «").append(escapeHtml(club.name)).append("»\n")
            append(categoryRu(club.category.literal)).append(" · ").append(escapeHtml(club.city))
        }

        val preparedMessageId = notificationService.savePreparedInviteMessage(
            sharerTelegramId = caller.telegramId,
            messageHtml = messageHtml,
            buttonText = "Открыть клуб",
            buttonUrl = inviteUrl
        )
        log.info(
            "Invite share created: clubId={} callerId={} prepared={}",
            clubId, callerId, preparedMessageId != null
        )
        return InviteShareDto(inviteUrl = inviteUrl, preparedMessageId = preparedMessageId)
    }

    // Приглашать может владелец и участник с ролью organizer (решение PO №5, club-invites.md).
    private fun requireOwnerOrOrganizer(club: Club, callerId: UUID) {
        if (club.ownerId == callerId) return
        val membership = membershipRepository.findActiveByUserAndClub(callerId, club.id)
        if (membership?.role != MembershipRole.organizer) {
            throw ForbiddenException("Only the club owner or organizer can share invites")
        }
    }

    // Русские подписи категорий для текста приглашения (enum club_category → подпись как на фронте).
    private fun categoryRu(literal: String): String = when (literal) {
        "sport" -> "Спорт"
        "creative" -> "Творчество"
        "food" -> "Еда"
        "board_games" -> "Настолки"
        "cinema" -> "Кино"
        "education" -> "Образование"
        "travel" -> "Путешествия"
        else -> "Другое"
    }

    // parse_mode=HTML: пользовательские строки (имя, название клуба, город) обязаны экранироваться.
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
