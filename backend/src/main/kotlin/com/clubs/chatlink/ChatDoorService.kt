package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

/**
 * «Дверь»: членство в чате = членство в клубе (стратегия §4). Бот не может добавить человека
 * в группу принудительно — вход всегда через chat_join_request по door-ссылке
 * (creates_join_request), который бот одобряет, когда у человека есть доступ к клубу.
 *
 * Все методы best-effort и вне HTTP-транзакций: сбой Telegram никогда не ломает
 * членство/заявки в приложении, только логируется.
 */
@Service
class ChatDoorService(
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(ChatDoorService::class.java)

    /**
     * Человек постучался в чат (chat_join_request). Ключевой момент: Telegram даёт боту
     * законное право один раз написать ему в личку — используем для «правил игры» (мокап 02-B).
     */
    fun onChatJoinRequest(chatId: Long, requesterTelegramId: Long) {
        val link = chatLinkRepository.findByChatId(chatId) ?: return
        if (!link.doorEnabled) return // дверь выключена — заявки разбирает организатор вручную
        val club = clubRepository.findById(link.clubId) ?: return

        val user = userRepository.findByTelegramId(requesterTelegramId)
        val membership = user?.id?.let { membershipRepository.findByUserAndClub(it, club.id) }

        when {
            membership != null && hasClubAccess(membership.status, membership.subscriptionExpiresAt) -> {
                // Уже свой — впускаем сразу, без DM (система молчит, когда всё хорошо).
                val approved = gateway.approveJoinRequest(chatId, requesterTelegramId)
                log.info("Door: member join request auto-approved={} clubId={} telegramId={}", approved, club.id, requesterTelegramId)
            }
            membership != null && membership.status in setOf(MembershipStatus.frozen, MembershipStatus.expired) -> {
                // Должник: заявка остаётся висеть, впустим автоматически после «Взнос получен».
                gateway.sendDmWithWebApp(
                    telegramId = requesterTelegramId,
                    text = "Ты уже в клубе «${club.name}», но доступ пока закрыт — оплати взнос организатору, и я сразу впущу тебя в чат.",
                    buttonText = "Открыть клуб",
                    webAppPath = "/clubs/${club.id}"
                )
                log.info("Door: frozen/expired member knocked, DM sent: clubId={} telegramId={}", club.id, requesterTelegramId)
            }
            else -> {
                // Чужой (или аккаунта в приложении ещё нет): объясняем правило игры, заявка висит
                // до одобрения в приложении (мокап 02-B).
                gateway.sendDmWithWebApp(
                    telegramId = requesterTelegramId,
                    text = "👋 Привет! Чат «${link.chatTitle ?: "клуба"}» принадлежит клубу «${club.name}».\n\n" +
                        "Вступление в чат — через клуб:\n" +
                        "1. Подай заявку в приложении\n" +
                        "2. Организатор одобрит — и я сразу впущу тебя в чат",
                    buttonText = "Открыть клуб «${club.name}»",
                    webAppPath = "/clubs/${club.id}"
                )
                log.info("Door: stranger knocked, rules DM sent: clubId={} telegramId={}", club.id, requesterTelegramId)
            }
        }
    }

    /**
     * Доступ к клубу открылся (вступление в бесплатный клуб / «Взнос получен» / разморозка).
     * Если человек стучался — одобряем его заявку; если нет и его ещё нет в чате — шлём
     * приглашение door-ссылкой (мокап 02-D: «нажал — и в чате», заявка одобрится по ветке
     * onChatJoinRequest как участнику с доступом).
     */
    @Async
    fun onAccessOpened(clubId: UUID, userId: UUID) {
        val link = chatLinkRepository.findByClubId(clubId) ?: return
        if (!link.doorEnabled || !link.botStatus.isInChat) return
        val doorLink = link.doorInviteLink ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val clubName = clubRepository.findById(clubId)?.name ?: "клуба"

        if (gateway.approveJoinRequest(link.chatId, telegramId)) {
            gateway.sendDmWithUrlButton(
                telegramId = telegramId,
                text = "🎉 Организатор открыл тебе доступ в клуб «$clubName» — ты уже в чате, загляни и представься!",
                buttonText = "Открыть чат",
                url = doorLink
            )
            log.info("Door: pending join request approved on access open: clubId={} userId={}", clubId, userId)
            return
        }

        // Заявки не было. Если человек ТОЧНО уже в чате (продление взноса, разморозка) — молчим.
        // При неизвестности (Telegram не ответил / базовая группа не отдаёт незнакомого юзера)
        // приглашение ШЛЁМ: систематически потерять вход для новичка хуже, чем изредка прислать
        // лишний DM действующему участнику чата.
        if (gateway.isChatParticipant(link.chatId, telegramId) == true) {
            log.info("Door: access opened, user already in chat — no DM: clubId={} userId={}", clubId, userId)
            return
        }
        gateway.sendDmWithUrlButton(
            telegramId = telegramId,
            text = "Организатор открыл тебе доступ в клуб «$clubName». Вступай в чат клуба:",
            buttonText = "Вступить в чат",
            url = doorLink
        )
        log.info("Door: invite DM sent on access open: clubId={} userId={}", clubId, userId)
    }

    /**
     * Заявка в клуб отклонена / участник исключён до входа: закрываем висящую chat-заявку.
     * Блайндовый decline — отсутствие заявки не ошибка. Бан уже-состоящих в чате — слайс
     * «строгий режим», здесь сознательно не делаем.
     */
    @Async
    fun onAccessRevoked(clubId: UUID, userId: UUID) {
        val link = chatLinkRepository.findByClubId(clubId) ?: return
        if (!link.doorEnabled) return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val declined = gateway.declineJoinRequest(link.chatId, telegramId)
        if (declined) {
            log.info("Door: pending join request declined on access revoke: clubId={} userId={}", clubId, userId)
        }
    }

    /**
     * «Доступ к чату есть»: active, либо отменённая платная подписка внутри оплаченного
     * периода (человек всё ещё в клубе — зеркалит ClubPage.isCancelledInPeriod).
     * frozen/expired — должники, им дверь не открываем.
     */
    private fun hasClubAccess(status: MembershipStatus, subscriptionExpiresAt: OffsetDateTime?): Boolean =
        status == MembershipStatus.active ||
            (status == MembershipStatus.cancelled &&
                subscriptionExpiresAt?.isAfter(OffsetDateTime.now()) == true)
}
