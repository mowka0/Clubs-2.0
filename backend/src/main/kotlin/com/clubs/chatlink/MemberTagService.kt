package com.clubs.chatlink

import com.clubs.award.AwardRepository
import com.clubs.award.AwardService
import com.clubs.award.TagSyncRow
import com.clubs.bot.ChatTelegramGateway
import com.clubs.club.ClubRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * «Теги наград» (слайс 4, Bot API 9.5, решения PO 2026-07-10): последняя выданная награда
 * участника видна тегом рядом с именем в чате — обычным member tag, БЕЗ повышения в админы
 * (setChatMemberTag; боту нужно право «Управление тегами»). Тег не конфликтует со строгим
 * режимом: mute/ban работают на тегированных участниках как на обычных.
 *
 * Помимо событийных обновлений (выдача/отзыв награды) работает шедулер полной синхронизации
 * [syncClub] (правила PO, «полная синхронность»):
 *  - награда в клубе есть, тега в чате нет → поставить тег;
 *  - награды в клубе нет, тег в чате есть → импортировать награду в приложение;
 *  - обе стороны заполнены/пусты → не трогаем (ручной тег организатора уважается).
 *
 * Все методы best-effort: сбой Telegram логируется и не ломает награды/членство.
 */
@Service
class MemberTagService(
    private val chatLinkRepository: ChatLinkRepository,
    private val tagRepository: ChatAwardTagRepository,
    private val awardRepository: AwardRepository,
    private val awardService: AwardService,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(MemberTagService::class.java)

    /** Набор наград участника изменился (выдача/отзыв) → пересчитать тег. */
    @Async
    fun onAwardChanged(clubId: UUID, userId: UUID) {
        val link = taggedLink(clubId) ?: return
        val telegramId = userRepository.findById(userId)?.telegramId ?: return
        val latestLabel = awardRepository.findByMember(clubId, userId).firstOrNull()?.label
        if (latestLabel == null) removeTag(link, telegramId)
        else applyTag(link, telegramId, latestLabel)
    }

    /**
     * Снять тег, если он выставлен НАМИ (уход из клуба / отзыв последней награды).
     * Учёт чистится только при успешном снятии — иначе тег остался бы висеть без следа.
     */
    fun removeTag(link: ChatLink, telegramId: Long) {
        tagRepository.find(link.clubId, telegramId) ?: return
        // Пустая строка снимает тег (setChatMemberTag).
        val removed = gateway.setMemberTag(link.chatId, telegramId, "")
        if (removed) tagRepository.delete(link.clubId, telegramId)
        log.info("Award tag removed: ok={} clubId={} telegramId={}", removed, link.clubId, telegramId)
    }

    /** Включение тумблера: теги всем живым участникам с наградами (по последней). */
    fun backfillForClub(link: ChatLink) {
        val rows = awardRepository.findTagSyncRows(link.clubId).filter { it.label != null }
        val tagged = rows.count { applyTag(link, it.telegramId, it.label!!) }
        log.info("Award tags backfill: tagged {}/{} clubId={}", tagged, rows.size, link.clubId)
    }

    /** Выключение тумблера / отвязка чата: снять все теги из учёта. */
    fun disableForClub(link: ChatLink) {
        val tags = tagRepository.findAllForClub(link.clubId)
        if (tags.isEmpty()) return
        val removed = tags.count { gateway.setMemberTag(link.chatId, it.telegramId, "") }
        tagRepository.deleteAllForClub(link.clubId)
        log.info("Award tags disabled: removed {}/{} clubId={}", removed, tags.size, link.clubId)
    }

    /**
     * Полная сверка клуба (шедулер, правила PO). Читает фактический тег каждого живого
     * участника (getChatMember — надёжнее событий, ловит ручные правки и «был не в чате»)
     * и заполняет пустую сторону; конфликтов не решает.
     */
    fun syncClub(link: ChatLink) {
        val rows = awardRepository.findTagSyncRows(link.clubId)
        rows.forEach { row ->
            val lookup = gateway.getMemberTag(link.chatId, row.telegramId) ?: return@forEach
            if (!lookup.inChat) return@forEach // не участник чата — тег ставить некуда
            val clubTag = row.label?.take(TAG_MAX_LENGTH)
            when {
                clubTag != null && lookup.tag == null -> applyTag(link, row.telegramId, clubTag)
                clubTag == null && lookup.tag != null -> importAward(link, row, lookup.tag)
                else -> Unit // обе стороны заполнены или обе пусты — правила PO не трогают
            }
        }
    }

    /**
     * Обратная синхронизация (правило PO): тег в чате есть, наград в клубе нет → тег
     * становится наградой в приложении (от имени владельца клуба, дефолтный эмодзи).
     * Выдача опубликует AwardGrantedEvent → applyTag зафиксирует тег в учёте.
     */
    private fun importAward(link: ChatLink, row: TagSyncRow, tag: String) {
        val ownerId = clubRepository.findById(link.clubId)?.ownerId ?: return
        try {
            awardService.grant(link.clubId, row.userId, IMPORTED_AWARD_EMOJI, tag, ownerId)
            log.info("Award imported from chat tag: clubId={} userId={}", link.clubId, row.userId)
        } catch (e: Exception) {
            // Лимит/дубль/гонка с выдачей — не валим сверку остальных участников.
            log.warn("Award import from chat tag failed: clubId={} userId={} error={}", link.clubId, row.userId, e.message)
        }
    }

    /** Выставить тег (обрезка до лимита Telegram; дедуп по учёту). */
    private fun applyTag(link: ChatLink, telegramId: Long, label: String): Boolean {
        val tag = label.take(TAG_MAX_LENGTH)
        if (tagRepository.find(link.clubId, telegramId)?.tag == tag) return true // уже стоит
        val tagged = gateway.setMemberTag(link.chatId, telegramId, tag)
        if (tagged) tagRepository.upsert(link.clubId, telegramId, tag)
        else log.warn("Award tag not applied: clubId={} telegramId={}", link.clubId, telegramId)
        return tagged
    }

    /** Привязка, по которой теги действуют: тумблер включён, бот в чате. */
    private fun taggedLink(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.awardTagsEnabled && it.botStatus.isInChat }

    companion object {
        // Лимит тега Telegram (Bot API 9.5); новые награды ≤16 по валидации, легаси обрезаются.
        const val TAG_MAX_LENGTH = 16
        // Эмодзи для наград, импортированных из ручных тегов чата (у тега эмодзи не бывает).
        const val IMPORTED_AWARD_EMOJI = "🏷️"
    }
}
