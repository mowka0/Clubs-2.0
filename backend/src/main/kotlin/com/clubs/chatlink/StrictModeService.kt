package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * «Строгий режим» (слайс 5, стратегия §4 «Завхоз прав»): приложение управляет физикой чата.
 * Должники клуба (frozen/expired) — «только чтение», покинувшие клуб любым путём — бан
 * (решения PO 2026-07-08, docs/design/club-chat-link/notes.md). Путь назад из бана —
 * повторное вступление в клуб: unban делает существующий флоу двери (ChatDoorService).
 *
 * Все методы best-effort: сбой Telegram логируется и никогда не ломает членство в приложении.
 * Организатор неуязвим по построению — его membership не бывает frozen/expired, а событий
 * ухода по нему не публикуется (owner не может выйти из своего клуба).
 */
@Service
class StrictModeService(
    private val chatLinkRepository: ChatLinkRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(StrictModeService::class.java)

    /** Доступ закрылся, человек остался в клубе должником (freeze / просрочка / ждёт первого взноса) → mute. */
    @Async
    fun onAccessClosed(clubId: UUID, userId: UUID) {
        val link = strictLink(clubId) ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val muted = gateway.muteChatMember(link.chatId, telegramId)
        log.info("Strict mode: debtor mute={} clubId={} userId={}", muted, clubId, userId)
    }

    /** Доступ открылся (взнос получен / разморозка / вступление) → вернуть голос, если был mute. */
    @Async
    fun onAccessOpened(clubId: UUID, userId: UUID) {
        val link = strictLink(clubId) ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val unmuted = gateway.unmuteChatMember(link.chatId, telegramId)
        log.info("Strict mode: member unmute={} clubId={} userId={}", unmuted, clubId, userId)
    }

    /** Человек покинул клуб (кик / отказ / отклонённая заявка / выход / истёкшая отменённая подписка) → ban. */
    @Async
    fun onMembershipRevoked(clubId: UUID, userId: UUID) {
        val link = strictLink(clubId) ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val banned = gateway.banChatMember(link.chatId, telegramId)
        log.info("Strict mode: leaver ban={} clubId={} userId={}", banned, clubId, userId)
    }

    /**
     * Backfill при включении тумблера (решение PO): mute всех ТЕКУЩИХ должников клуба —
     * организатор видит эффект немедленно. Бан-backfill сознательно не делается (форвард-онли).
     * Вызывается в транзакции тумблера (паттерн живого закрепа — hardening отложен).
     */
    fun backfillForClub(link: ChatLink) {
        val debtors = membershipRepository.findDebtorTelegramIds(link.clubId)
        val muted = debtors.count { gateway.muteChatMember(link.chatId, it) }
        log.info("Strict mode backfill: muted {}/{} debtors clubId={}", muted, debtors.size, link.clubId)
    }

    /**
     * Выключение тумблера / отвязка чата: вернуть голос текущим должникам (unmute трогает
     * только restricted — ручные ограничения обычных участников не задеваются). Баны не
     * снимаются — множество «забаненных нами» невосстановимо без отдельного учёта, а путь
     * назад через повторное вступление работает и без тумблера.
     */
    fun disableForClub(link: ChatLink) {
        val debtors = membershipRepository.findDebtorTelegramIds(link.clubId)
        val unmuted = debtors.count { gateway.unmuteChatMember(link.chatId, it) }
        log.info("Strict mode disable: unmuted {}/{} debtors clubId={}", unmuted, debtors.size, link.clubId)
    }

    /** Привязка клуба, по которой строгий режим вообще действует: тумблер включён, бот в чате. */
    private fun strictLink(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.strictModeEnabled && it.botStatus.isInChat }
}
