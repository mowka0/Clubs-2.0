package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.bot.PARSE_MODE_HTML
import com.clubs.bot.UserChatState
import com.clubs.debt.DebtRepository
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * «Живой статус сбора» (club-chat-link слайс 3.5, тексты — skladchina-v3 § 6): у бота ОДНО
 * сообщение-статус на сбор, перерисовывается на каждое изменение долга. Механика зеркалит
 * [LivePinService]: dirty-флаги в памяти + flush-планировщик с дебаунсом
 * (`chatlink.skladchina-status-flush-ms`), рестарт теряет несброшенные флаги безболезненно.
 *
 * Тихий voluntary (hidden_from_user_id) поста не имеет вовсе. Close-проход сканирует БД по
 * СТАТУСУ сбора: каскады cancelActiveByClub / cancelActiveByEventId минуют сервисы и событий
 * не публикуют. Всё best-effort: сбой Telegram логируется в шлюзе и не валит бизнес-операцию.
 */
@Service
class SkladchinaChatStatusService(
    private val chatLinkRepository: ChatLinkRepository,
    private val postRepository: SkladchinaChatPostRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val userRepository: UserRepository,
    private val renderer: SkladchinaChatStatusRenderer,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(SkladchinaChatStatusService::class.java)

    // Dirty-флаги перерисовки: сбор попал сюда → при ближайшем flush его статус перечитывается из
    // БД и редактируется. Пишут AFTER_COMMIT-листенеры из разных потоков.
    private val dirtySkladchinaIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun markDirty(skladchinaId: UUID) {
        dirtySkladchinaIds.add(skladchinaId)
    }

    /**
     * Создание сбора: пост-статус, если тумблер включён и сбор не тихий. Возвращает chatId, когда
     * живой пост фактически существует, — маршрутизатор ([com.clubs.bot.ChatAwareBroadcast])
     * подавит DM участникам этого чата. Вызывается синхронно из @Async-оркестратора
     * SkladchinaBotNotifier (возврат значения из @Async-метода терялся бы).
     */
    @Transactional
    fun onSkladchinaCreated(clubId: UUID, skladchinaId: UUID): Long? {
        val link = liveLinkFor(clubId) ?: return null
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return null
        return if (createPost(link, skladchina)) link.chatId else null
    }

    /** Закрытие сбора (собран, отменён, не набрали): немедленный финальный edit + unpin, не ждём flush. */
    @Async
    @Transactional
    fun closeNow(skladchinaId: UUID) {
        val post = postRepository.findBySkladchinaId(skladchinaId) ?: return
        if (post.closedAt != null) return
        closePost(post)
    }

    /** Включение тумблера: backfill — статус-пост для всех АКТИВНЫХ сборов клуба без живого поста. */
    @Transactional
    fun backfillForClub(clubId: UUID) {
        val link = liveLinkFor(clubId) ?: return
        skladchinaRepository.findActiveByClub(clubId).forEach { createPost(link, it) }
    }

    /**
     * Выключение тумблера / отвязка чата: живые статусы открепляются, строки удаляются —
     * повторное включение создаст свежие посты. Сообщения остаются в истории чата.
     */
    @Transactional
    fun disableForClub(link: ChatLink) {
        postRepository.findOpenByChatId(link.chatId).forEach { post ->
            gateway.unpinChatMessage(post.chatId, post.messageId)
            postRepository.delete(post.skladchinaId)
        }
        log.info("Skladchina chat statuses disabled: clubId={} chatId={}", link.clubId, link.chatId)
    }

    /**
     * Напоминание о сроке в чат вместо DM. Возвращает id должников, покрытых чат-упоминанием
     * (они В ЧАТЕ и упомянуты) — вызывающий шлёт DM остальным. Пустой сет = чат-канал недоступен
     * (тумблер выключен / поста нет / отправка не удалась) — фоллбек на DM всем.
     */
    fun postDeadlineReminder(skladchina: Skladchina, pendingUserIds: List<UUID>): Set<UUID> {
        val deadline = skladchina.deadline ?: return emptySet()
        val link = liveLinkFor(skladchina.clubId) ?: return emptySet()
        val post = postRepository.findBySkladchinaId(skladchina.id)
        if (post == null || post.closedAt != null) return emptySet()

        // Пинг доходит только до участников ЧАТА: остальные получат прежний DM. UNKNOWN
        // (Telegram молчит) считаем «не в чате» — лишний DM лучше потерянного напоминания.
        val inChat = userRepository.findByIds(pendingUserIds)
            .filter { gateway.getUserChatState(link.chatId, it.telegramId) == UserChatState.IN_CHAT }
            .sortedBy { it.firstName }
            .take(SkladchinaChatStatusRenderer.MAX_MENTIONS)
        if (inChat.isEmpty()) return emptySet()

        val mentions = inChat.map { ChatMention(it.telegramId, it.firstName) }
        gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderer.reminderText(skladchina.title, deadline, mentions),
            buttonText = renderer.buttonText(skladchina),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        ) ?: return emptySet()

        log.info("Skladchina chat reminder posted: skladchinaId={} chatId={} mentioned={} of pending={}",
            skladchina.id, link.chatId, inChat.size, pendingUserIds.size)
        return inChat.mapNotNull { it.id }.toSet()
    }

    /**
     * Flush-планировщик: (1) перерисовать dirty-статусы, (2) close-проход — закрыть статусы
     * сборов, которые уже не активны (в т.ч. каскадные отмены без доменного события).
     */
    @Scheduled(fixedDelayString = "\${chatlink.skladchina-status-flush-ms:30000}")
    @Transactional
    fun flush() {
        val batch = dirtySkladchinaIds.toList()
        batch.forEach { skladchinaId ->
            dirtySkladchinaIds.remove(skladchinaId)
            refreshPost(skladchinaId)
        }
        postRepository.findOpenPostsOfInactiveSkladchinas().forEach { closePost(it) }
    }

    /** TRUE = живой пост существует после попытки (уже был, только что создан или создан конкурентом). */
    private fun createPost(link: ChatLink, skladchina: Skladchina): Boolean {
        if (!skladchina.isActive || skladchina.hiddenFromUserId != null) return false
        if (postRepository.findBySkladchinaId(skladchina.id) != null) return true
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderer.statusText(buildView(skladchina)),
            buttonText = renderer.buttonText(skladchina),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        )
        if (messageId == null) {
            // Пост не удался — строку не создаём: повторная попытка при следующем включении тумблера/backfill.
            log.warn("Skladchina chat status post failed: skladchinaId={} chatId={}", skladchina.id, link.chatId)
            return false
        }
        // Гонка backfill × onSkladchinaCreated: проигравший не роняет транзакцию на PK-конфликте,
        // а просто не закрепляет (его сообщение останется в чате дублем — редкое окно, best-effort).
        if (!postRepository.insertIfAbsent(SkladchinaChatPost(skladchina.id, link.chatId, messageId, closedAt = null))) {
            log.info("Skladchina chat status already posted by concurrent path: skladchinaId={}", skladchina.id)
            return true
        }
        // notify = true — сбор должен увидеть весь чат. Один пуш на создание, перерисовки молчат.
        if (link.canPinMessages) gateway.pinChatMessage(link.chatId, messageId, notify = true)
        log.info("Skladchina chat status created: skladchinaId={} chatId={} messageId={}",
            skladchina.id, link.chatId, messageId)
        return true
    }

    private fun refreshPost(skladchinaId: UUID) {
        val post = postRepository.findBySkladchinaId(skladchinaId) ?: return
        if (post.closedAt != null) return
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        // Не-активный закроет close-проход (или уже закрыл closeNow) — здесь не трогаем.
        if (!skladchina.isActive) return
        gateway.editGroupMessage(
            chatId = post.chatId,
            messageId = post.messageId,
            text = renderer.statusText(buildView(skladchina)),
            buttonText = renderer.buttonText(skladchina),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        )
    }

    /** Финальный edit + unpin по состоянию из БД; строка закрывается даже при сбое, иначе мёртвый пост ретраился бы вечно. */
    private fun closePost(post: SkladchinaChatPost) {
        val skladchina = skladchinaRepository.findById(post.skladchinaId)
        if (skladchina != null) {
            gateway.editGroupMessage(post.chatId, post.messageId, renderer.closedText(buildView(skladchina)), null, null, PARSE_MODE_HTML)
            gateway.unpinChatMessage(post.chatId, post.messageId)
        }
        postRepository.markClosed(post.skladchinaId)
        log.info("Skladchina chat status closed: skladchinaId={} chatId={} status={}",
            post.skladchinaId, post.chatId, skladchina?.status)
    }

    private fun buildView(skladchina: Skladchina): ChatStatusView {
        val totals = debtRepository.totals(skladchina.id)
        val waitingIds = debtRepository.findBySkladchina(skladchina.id)
            .filter { it.debt.status == DebtStatus.waiting || it.debt.status == DebtStatus.promised }
            .map { it.debt.debtorId }
        val enrolledIds = if (skladchina.enrollmentUntil != null) skladchinaRepository.findEnrolledUserIds(skladchina.id) else emptyList()
        return ChatStatusView(
            skladchina = skladchina,
            totals = totals,
            enrolledCount = enrolledIds.size,
            creatorName = userRepository.findById(skladchina.creatorId)?.firstName ?: "",
            waiting = mentions(waitingIds),
            enrolled = mentions(enrolledIds),
            now = OffsetDateTime.now()
        )
    }

    // Стабильный порядок упоминаний (по имени) — иначе перестановка списка давала бы «пустые»
    // edit'ы, которые Telegram не дедуплицирует по «message is not modified».
    private fun mentions(userIds: List<UUID>): List<ChatMention> =
        if (userIds.isEmpty()) emptyList()
        else userRepository.findByIds(userIds).sortedBy { it.firstName }.map { ChatMention(it.telegramId, it.firstName) }

    /** Привязка клуба, если статус сборов включён и бот в чате; иначе null (фича молчит). */
    private fun liveLinkFor(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.skladchinaStatusEnabled && it.botStatus.isInChat }
}
