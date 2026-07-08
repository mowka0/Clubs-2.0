package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.bot.PARSE_MODE_HTML
import com.clubs.bot.UserChatState
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * «Живой статус сбора» (слайс 3.5 club-chat-link): у бота ОДНО сообщение-статус на складчину —
 * прогресс «Скинулись N из M», дедлайн, «Ждём:» с text_mention-упоминаниями. Механика зеркалит
 * [LivePinService]: dirty-флаги в памяти + flush-планировщик с дебаунсом
 * (`chatlink.skladchina-status-flush-ms`), рестарт теряет несброшенные флаги безболезненно.
 *
 * Отличия от закрепа событий: close-проход сканирует БД по СТАТУСУ складчины (не по времени) —
 * это обязательная механика закрытия, а не страховка: каскады cancelActiveByClub /
 * cancelActiveByEventId минуют closeInternal и не публикуют SkladchinaClosedEvent.
 * Всё best-effort: сбой Telegram логируется в шлюзе и не валит бизнес-операцию.
 */
@Service
class SkladchinaChatStatusService(
    private val chatLinkRepository: ChatLinkRepository,
    private val postRepository: SkladchinaChatPostRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val userRepository: UserRepository,
    private val renderer: SkladchinaChatStatusRenderer,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(SkladchinaChatStatusService::class.java)

    // Dirty-флаги перерисовки: складчина попала сюда → при ближайшем flush её статус
    // перечитывается из БД и редактируется. Пишут AFTER_COMMIT-листенеры из разных потоков.
    private val dirtySkladchinaIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun markDirty(skladchinaId: UUID) {
        dirtySkladchinaIds.add(skladchinaId)
    }

    /** Создание складчины: пост-статус (пинг №1 — упоминания в «Ждём:»), если тумблер включён. */
    @Async
    @Transactional
    fun onSkladchinaCreated(clubId: UUID, skladchinaId: UUID) {
        val link = liveLinkFor(clubId) ?: return
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        createPost(link, skladchina)
    }

    /** Закрытие складчины: немедленный финальный edit + unpin (не ждём flush). */
    @Async
    @Transactional
    fun onSkladchinaClosed(event: SkladchinaClosedEvent) {
        val post = postRepository.findBySkladchinaId(event.skladchinaId) ?: return
        if (post.closedAt != null) return
        val text = renderer.closedText(event.title, event.finalStatus, event.paidCount, event.participantCount)
        gateway.editGroupMessage(post.chatId, post.messageId, text, null, null, PARSE_MODE_HTML)
        gateway.unpinChatMessage(post.chatId, post.messageId)
        postRepository.markClosed(event.skladchinaId)
        log.info("Skladchina chat status closed: skladchinaId={} chatId={} finalStatus={}",
            event.skladchinaId, post.chatId, event.finalStatus)
    }

    /** Включение тумблера: backfill — статус-пост для всех АКТИВНЫХ складчин клуба без живого поста. */
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
     * Напоминание о дедлайне в чат вместо DM (пинг №2). Возвращает id участников, покрытых
     * чат-упоминанием (они В ЧАТЕ и упомянуты) — вызывающий шлёт DM остальным.
     * Пустой сет = чат-канал недоступен (тумблер выключен / поста нет / отправка не удалась) —
     * фоллбек на DM всем, напоминание не должно теряться.
     */
    fun postDeadlineReminder(skladchina: Skladchina, pendingUserIds: List<UUID>): Set<UUID> {
        val link = liveLinkFor(skladchina.clubId) ?: return emptySet()
        val post = postRepository.findBySkladchinaId(skladchina.id)
        if (post == null || post.closedAt != null) return emptySet()

        // Пинг доходит только до участников ЧАТА: остальные получат прежний DM. UNKNOWN
        // (Telegram молчит) считаем «не в чате» — лишний DM лучше потерянного напоминания.
        val inChat = userRepository.findByIds(pendingUserIds)
            .filter { it.telegramId != null }
            .filter { gateway.getUserChatState(link.chatId, it.telegramId!!) == UserChatState.IN_CHAT }
            .sortedBy { it.firstName }
            .take(SkladchinaChatStatusRenderer.MAX_MENTIONS)
        if (inChat.isEmpty()) return emptySet()

        val mentions = inChat.map { ChatMention(it.telegramId!!, it.firstName ?: "Участник") }
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderer.reminderText(skladchina.title, skladchina.deadline, mentions),
            buttonText = renderer.buttonText(),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        ) ?: return emptySet()

        log.info("Skladchina chat reminder posted: skladchinaId={} chatId={} mentioned={} of pending={}",
            skladchina.id, link.chatId, inChat.size, pendingUserIds.size)
        return inChat.mapNotNull { it.id }.toSet()
    }

    /**
     * Flush-планировщик: (1) перерисовать dirty-статусы, (2) close-проход — закрыть статусы
     * складчин, которые уже не активны (в т.ч. каскадные отмены без доменного события).
     */
    @Scheduled(fixedDelayString = "\${chatlink.skladchina-status-flush-ms:30000}")
    @Transactional
    fun flush() {
        val batch = dirtySkladchinaIds.toList()
        batch.forEach { skladchinaId ->
            dirtySkladchinaIds.remove(skladchinaId)
            refreshPost(skladchinaId)
        }
        postRepository.findOpenPostsOfInactiveSkladchinas().forEach { closeFromDb(it) }
    }

    private fun createPost(link: ChatLink, skladchina: Skladchina) {
        if (skladchina.status != SkladchinaStatus.active) return
        if (postRepository.findBySkladchinaId(skladchina.id) != null) return
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderStatus(skladchina),
            buttonText = renderer.buttonText(),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        )
        if (messageId == null) {
            // Пост не удался — строку не создаём: повторная попытка при следующем включении
            // тумблера/backfill, здоровье чата организатор видит в табе «Чат».
            log.warn("Skladchina chat status post failed: skladchinaId={} chatId={}", skladchina.id, link.chatId)
            return
        }
        // Гонка backfill × onSkladchinaCreated: оба могли пройти проверку выше и отправить пост.
        // Проигравший не роняет транзакцию тумблера на PK-конфликте, а просто не закрепляет
        // (его сообщение останется в чате дублем — редкое окно, best-effort).
        if (!postRepository.insertIfAbsent(SkladchinaChatPost(skladchina.id, link.chatId, messageId, closedAt = null))) {
            log.info("Skladchina chat status already posted by concurrent path: skladchinaId={}", skladchina.id)
            return
        }
        // Право закрепа могли и не выдать: пост уходит без pin, статус всё равно живёт.
        if (link.canPinMessages) gateway.pinChatMessage(link.chatId, messageId)
        log.info("Skladchina chat status created: skladchinaId={} chatId={} messageId={}",
            skladchina.id, link.chatId, messageId)
    }

    private fun refreshPost(skladchinaId: UUID) {
        val post = postRepository.findBySkladchinaId(skladchinaId) ?: return
        if (post.closedAt != null) return
        val skladchina = skladchinaRepository.findById(skladchinaId) ?: return
        // Не-активную закроет close-проход (или уже закрыл onSkladchinaClosed) — здесь не трогаем.
        if (skladchina.status != SkladchinaStatus.active) return
        gateway.editGroupMessage(
            chatId = post.chatId,
            messageId = post.messageId,
            text = renderStatus(skladchina),
            buttonText = renderer.buttonText(),
            url = renderer.skladchinaUrl(skladchina.id),
            parseMode = PARSE_MODE_HTML
        )
    }

    /** Закрытие статуса close-проходом (складчина уже не активна в БД, событие могло не прийти). */
    private fun closeFromDb(post: SkladchinaChatPost) {
        val skladchina = skladchinaRepository.findById(post.skladchinaId)
        if (skladchina != null) {
            val paid = skladchinaRepository.countParticipantsByStatus(post.skladchinaId, SkladchinaParticipantStatus.paid)
            val total = skladchinaRepository.countParticipants(post.skladchinaId)
            val text = renderer.closedText(skladchina.title, skladchina.status, paid, total)
            gateway.editGroupMessage(post.chatId, post.messageId, text, null, null, PARSE_MODE_HTML)
            gateway.unpinChatMessage(post.chatId, post.messageId)
        }
        // Закрываем даже при сбое edit/unpin — иначе мёртвый пост ретраился бы вечно.
        postRepository.markClosed(post.skladchinaId)
        log.info("Skladchina chat status closed (close-pass): skladchinaId={} chatId={}", post.skladchinaId, post.chatId)
    }

    private fun renderStatus(skladchina: Skladchina): String {
        val paid = skladchinaRepository.countParticipantsByStatus(skladchina.id, SkladchinaParticipantStatus.paid)
        val total = skladchinaRepository.countParticipants(skladchina.id)
        val pendingIds = skladchinaRepository.findParticipants(skladchina.id)
            .filter { it.status == SkladchinaParticipantStatus.pending }
            .map { it.userId }
        // Стабильный порядок упоминаний (по имени) — иначе перестановка списка давала бы
        // «пустые» edit'ы, которые Telegram не дедуплицирует по «message is not modified».
        val mentions = userRepository.findByIds(pendingIds)
            .filter { it.telegramId != null }
            .sortedBy { it.firstName }
            .map { ChatMention(it.telegramId!!, it.firstName ?: "Участник") }
        return renderer.statusText(skladchina.title, paid, total, skladchina.deadline, mentions)
    }

    /** Привязка клуба, если статус сборов включён и бот в чате; иначе null (фича молчит). */
    private fun liveLinkFor(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.skladchinaStatusEnabled && it.botStatus.isInChat }
}
