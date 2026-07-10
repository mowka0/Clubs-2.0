package com.clubs.chatlink

import com.clubs.award.AwardRepository
import com.clubs.bot.ChatTelegramGateway
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * «Титулы наград» (слайс 4): последняя выданная награда участника видна в чате титулом рядом
 * с именем (решение PO 2026-07-10). Механика Telegram: бот повышает участника в «минимального
 * админа» (одно безвредное право — видеочаты; нулевые права Bot API трактует как демоут) и
 * ставит custom title. Рамки: титул ≤16 символов, ≤50 админов на группу, менять титулы бот
 * может только тем, кого сам повысил.
 *
 * Взаимодействие со строгим режимом (слайс 5): Telegram-админ неподвластен mute/ban, поэтому
 * StrictModeService ПЕРЕД рычагом вызывает [removeTitle]; при включённом строгом режиме
 * должники не титулуются вовсе (иначе выдача награды замьюченному вернула бы ему голос).
 *
 * Все методы best-effort: сбой Telegram логируется и не ломает награды/членство.
 */
@Service
class TitleService(
    private val chatLinkRepository: ChatLinkRepository,
    private val titleRepository: ChatAwardTitleRepository,
    private val awardRepository: AwardRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(TitleService::class.java)

    /** Набор наград участника изменился (выдача/отзыв) → пересчитать титул. */
    @Async
    fun onAwardChanged(clubId: UUID, userId: UUID) {
        val link = titledLink(clubId) ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        refreshTitle(link, userId, telegramId)
    }

    /**
     * Доступ открылся (вернулся в клуб / оплатил взнос) → восстановить титул, снятый уходом
     * или строгим режимом. Вызывается из StrictModeService ПОСЛЕ unmute (один @Async-поток —
     * без гонки с рычагами).
     */
    fun restoreTitle(link: ChatLink, userId: UUID, telegramId: Long) {
        if (!link.awardTitlesEnabled) return
        refreshTitle(link, userId, telegramId)
    }

    /**
     * Снять титул (демоут «минимального админа»), если он выставлен НАМИ. Вызывается строгим
     * режимом перед mute/ban (админ неподвластен рычагам) и при уходе из клуба. Учёт чистится
     * только при успешном демоуте — иначе титул остался бы висеть без следа.
     */
    fun removeTitle(link: ChatLink, telegramId: Long) {
        titleRepository.find(link.clubId, telegramId) ?: return
        val demoted = gateway.demoteTitledAdmin(link.chatId, telegramId)
        if (demoted) titleRepository.delete(link.clubId, telegramId)
        log.info("Award title removed: demoted={} clubId={} telegramId={}", demoted, link.clubId, telegramId)
    }

    /** Включение тумблера: титулы всем живым участникам с наградами (по последней). */
    fun backfillForClub(link: ChatLink) {
        val candidates = awardRepository.findTitleCandidates(link.clubId)
        val titled = candidates.count { candidate ->
            // При включённом строгом режиме должник замьючен — титул сделал бы его админом и вернул голос.
            if (link.strictModeEnabled && candidate.membershipStatus in DEBTOR_LITERALS) return@count false
            applyTitle(link, candidate.telegramId, candidate.label)
        }
        log.info("Award titles backfill: titled {}/{} candidates clubId={}", titled, candidates.size, link.clubId)
    }

    /** Выключение тумблера / отвязка чата: снять все титулы из учёта. */
    fun disableForClub(link: ChatLink) {
        val titles = titleRepository.findAllForClub(link.clubId)
        if (titles.isEmpty()) return
        val demoted = titles.count { gateway.demoteTitledAdmin(link.chatId, it.telegramId) }
        titleRepository.deleteAllForClub(link.clubId)
        log.info("Award titles disabled: demoted {}/{} clubId={}", demoted, titles.size, link.clubId)
    }

    /** Пересчёт титула участника: последняя награда → выставить; наград нет → снять. */
    private fun refreshTitle(link: ChatLink, userId: UUID, telegramId: Long) {
        val latestLabel = awardRepository.findByMember(link.clubId, userId).firstOrNull()?.label
        if (latestLabel == null) {
            removeTitle(link, telegramId)
            return
        }
        val membership = membershipRepository.findByUserAndClub(userId, link.clubId)
        // Ушедшего не титулуем; должника — только при выключенном строгом режиме (см. KDoc класса).
        if (membership == null || membership.status == MembershipStatus.cancelled) return
        if (link.strictModeEnabled && membership.status in DEBTOR_STATUSES) return
        applyTitle(link, telegramId, latestLabel)
    }

    /**
     * Выставить титул. Повышаем только если участник ещё не в учёте (повторный promote нашего
     * же админа Telegram может отклонить); неудачный promote не блокирует попытку setTitle —
     * участник мог остаться нашим админом при потерянном учёте. Учёт пишется только при
     * успешно выставленном титуле; легаси-награды длиннее лимита обрезаются.
     */
    private fun applyTitle(link: ChatLink, telegramId: Long, label: String): Boolean {
        val title = label.take(TITLE_MAX_LENGTH)
        val existing = titleRepository.find(link.clubId, telegramId)
        if (existing?.title == title) return true // уже стоит — no-op
        if (existing == null) gateway.promoteToTitledAdmin(link.chatId, telegramId)
        val titled = gateway.setAdminCustomTitle(link.chatId, telegramId, title)
        if (titled) titleRepository.upsert(link.clubId, telegramId, title)
        else log.warn("Award title not applied: clubId={} telegramId={}", link.clubId, telegramId)
        return titled
    }

    /** Привязка, по которой титулы действуют: тумблер включён, бот в чате. */
    private fun titledLink(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.awardTitlesEnabled && it.botStatus.isInChat }

    companion object {
        // Лимит титула Telegram; новые награды ≤16 по валидации, легаси обрезаются.
        const val TITLE_MAX_LENGTH = 16
        // Должники (доступ закрыт): при включённом строгом режиме их не титулуем.
        private val DEBTOR_STATUSES = setOf(MembershipStatus.frozen, MembershipStatus.expired)
        private val DEBTOR_LITERALS = DEBTOR_STATUSES.map { it.literal }.toSet()
    }
}
