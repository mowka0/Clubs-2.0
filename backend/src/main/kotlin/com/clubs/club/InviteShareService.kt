package com.clubs.club

import com.clubs.bot.NotificationService
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Личные приглашения в клуб (club-invites, кадры A–C мокапа): собирает данные для боттом-шита
 * «Пригласить» — deep-link на посадочную `/invite/<code>` и prepared message для нативного
 * пикера Telegram (сообщение уходит от имени приглашающего, не от бота — бот не может писать
 * незнакомцам и не видит контакты).
 *
 * Приглашать может ЛЮБОЙ участник клуба, не только менеджер (решение PO 2026-07-30).
 */
@Service
class InviteShareService(
    private val clubService: ClubService,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    // Username бота — основа deep-link'а t.me/<bot>?startapp=…
    @Value("\${telegram.bot-username}") private val botUsername: String
) {

    private val log = LoggerFactory.getLogger(InviteShareService::class.java)

    fun createShare(clubId: UUID, callerId: UUID): InviteShareDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        // Гейт совпадает с гейтом ростера (MemberService.getClubMembers): кто видит состав клуба,
        // тот и зовёт. Статус строго `active` — зеркалит MembershipAccess.hasAccess(), поэтому
        // должник (frozen/expired) и вышедший приглашать не могут. Владелец проходит отдельной
        // ветвью: его membership создаётся вместе с клубом, но у легаси-клубов строки может не быть,
        // а до 2026-07-30 он проходил здесь через owner-bypass капабилити — регрессии быть не должно.
        val callerMembership = membershipRepository.findByUserAndClub(callerId, clubId)
        val isOwner = club.ownerId == callerId
        val isActiveMember = callerMembership?.status == MembershipStatus.active
        if (!isOwner && !isActiveMember) {
            throw ForbiddenException("Приглашать в клуб может его участник")
        }
        // Текст приглашения зависит от роли: у менеджера клуб «мой», у обычного участника «наш».
        val fromManager = isOwner || clubRoleGuard.isActiveManagerMembership(callerMembership)

        val code = clubService.ensureInviteCode(clubId)
        val inviteUrl = "https://t.me/$botUsername?startapp=invite_$code"

        val caller = userRepository.findById(callerId) ?: throw NotFoundException("User not found")

        // Кадр C мокапа: личное обращение (имя не нужно — отправитель и есть приглашающий) +
        // «карточка» клуба цитатой (blockquote ≈ выделенный блок) + сниппет описания с призывом.
        val messageHtml = buildString {
            append(if (fromManager) "<b>Приглашаю тебя в мой клуб!</b>\n" else "<b>Приглашаю тебя в наш клуб!</b>\n")
            append("<blockquote><b>").append(escapeHtml(club.name)).append("</b>\n")
            append(categoryRu(club.category.literal)).append(" · ").append(escapeHtml(club.city))
            append(" · ").append(club.memberCount).append(' ').append(membersWord(club.memberCount))
            append("</blockquote>")
            val snippet = club.description.trim().let { if (it.length > DESCRIPTION_SNIPPET_MAX) it.take(DESCRIPTION_SNIPPET_MAX).trimEnd() + "…" else it }
            if (snippet.isNotBlank()) append(escapeHtml(snippet)).append('\n')
            append("Посмотри и вступай 👇")
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

    // parse_mode=HTML: пользовательские строки (название клуба, город, описание) обязаны экранироваться.
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Русское склонение «участник» для строки-меты карточки.
    private fun membersWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "участников"
            mod10 == 1 -> "участник"
            mod10 in 2..4 -> "участника"
            else -> "участников"
        }
    }

    private companion object {
        // Максимум символов описания клуба в карточке-приглашении (дальше — многоточие).
        const val DESCRIPTION_SNIPPET_MAX = 120
    }
}
