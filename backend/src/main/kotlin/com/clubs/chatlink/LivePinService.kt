package com.clubs.chatlink

import com.clubs.bot.ChatTelegramGateway
import com.clubs.event.Event
import com.clubs.event.EventRepository
import com.clubs.event.EventResponseRepository
import com.clubs.event.OPEN_IN_YANDEX_MAPS_BUTTON
import com.clubs.event.yandexMapsUrl
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * «Живой закреп» (слайс 3 club-chat-link, мокап 03): у бота ОДНО закреплённое сообщение-статус
 * на событие, которое он редактирует, и один пост-итог после отметки явки. Всё остальное — тишина.
 *
 * Дебаунс: изменения ростера лишь ставят dirty-флаг в памяти ([markDirty]); реальную перерисовку
 * делает flush-планировщик раз в `chatlink.live-pin-flush-ms` (дефолт 30 сек) — шторм голосов
 * превращается максимум в один edit на событие за период и не упирается в лимиты Telegram.
 * Рестарт бэкенда теряет несброшенные флаги — следующее изменение ростера перерисует закреп;
 * close-проход от флагов не зависит (сканирует БД), закрытие закрепов надёжно.
 *
 * Всё best-effort: сбой Telegram логируется в шлюзе и не валит бизнес-операцию (fail-open
 * запрещён только для впуска в чат — тут его нет).
 */
@Service
class LivePinService(
    private val chatLinkRepository: ChatLinkRepository,
    private val pinRepository: EventChatPinRepository,
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val renderer: LivePinRenderer,
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(LivePinService::class.java)

    // Dirty-флаги перерисовки: событие попало сюда → при ближайшем flush его закреп
    // перечитывается из БД и редактируется. ConcurrentHashMap-set — markDirty зовут
    // AFTER_COMMIT-листенеры из разных потоков.
    private val dirtyEventIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun markDirty(eventId: UUID) {
        dirtyEventIds.add(eventId)
    }

    /**
     * Создание события: пост-статус + pin, если у клуба включён живой закреп.
     * Возвращает chatId, когда живой пост фактически существует после попытки, — маршрутизатор
     * рассылок ([com.clubs.bot.ChatAwareBroadcast]) подавит DM участникам этого чата.
     * Вызывается синхронно из @Async-оркестратора EventBotNotifier (не сам @Async —
     * возврат значения из @Async-метода терялся бы).
     */
    @Transactional
    fun onEventCreated(event: Event): Long? {
        val link = liveLinkFor(event.clubId) ?: return null
        return if (createPin(link, event)) link.chatId else null
    }

    /**
     * Отмена события: тихая правка закрепа + unpin немедленно (не ждём flush) + отдельный
     * ГРОМКИЙ пост об отмене — правки по механике Telegram никого не уведомляют, а после
     * маршрутизации пост становится единственным пингом для участников чата.
     * Возвращает chatId, когда пост отмены фактически вышел (для DM-фоллбека).
     */
    @Transactional
    fun onEventCancelled(event: Event, reason: String?): Long? {
        pinRepository.findByEventId(event.id)?.takeIf { it.closedAt == null }?.let { pin ->
            pin.messageId?.let { messageId ->
                gateway.editGroupMessage(pin.chatId, messageId, renderer.cancelledText(event, reason), null, null)
                gateway.unpinChatMessage(pin.chatId, messageId)
            }
            pinRepository.markClosed(event.id)
            log.info("Live pin closed (event cancelled): eventId={} chatId={}", event.id, pin.chatId)
        }
        val link = liveLinkFor(event.clubId) ?: return null
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderer.cancelledText(event, reason),
            buttonText = null,
            url = null
        )
        return if (messageId != null) link.chatId else null
    }

    /**
     * Первая отметка явки: пост-итог (кадр C мокапа) — ровно один раз на событие
     * (атомарный claim в event_chat_pins защищает от дублей при повторных отметках).
     */
    @Async
    @Transactional
    fun onAttendanceMarked(eventId: UUID) {
        val event = eventRepository.findById(eventId) ?: return
        val link = liveLinkFor(event.clubId) ?: return

        // Организатор мог отметить явку раньше close-прохода — сперва закрыть живой закреп.
        pinRepository.findByEventId(eventId)?.takeIf { it.closedAt == null }?.let { closePin(it, event) }

        if (!pinRepository.tryClaimSummary(eventId, link.chatId)) return

        val attended = eventResponseRepository.findAttendedUserIds(eventId).size
        val confirmedTotal = eventResponseRepository.countConfirmed(eventId)
        val meetingNumber = eventRepository.countPastEvents(event.clubId, OffsetDateTime.now())
        val firstTimers = eventResponseRepository.findFirstTimeAttendeeFirstNames(eventId, event.clubId)
        val nextEvent = eventRepository.findFutureEventsByClub(event.clubId, OffsetDateTime.now()).firstOrNull()

        val text = renderer.summaryText(meetingNumber, attended, confirmedTotal, firstTimers, nextEvent)
        // Тихий пост (решение PO 2026-07-08): итог встречи — фоновая сводка, пуш всем участникам
        // чата не нужен; громкое уведомление об отметке явки и так живёт в личных DM.
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = text,
            buttonText = nextEvent?.let { "Иду на следующую" },
            url = nextEvent?.let { renderer.eventUrl(it.id) },
            silent = true
        )
        if (messageId != null) {
            pinRepository.setSummaryMessageId(eventId, messageId)
            log.info("Meeting summary posted: eventId={} chatId={} attended={}/{}", eventId, link.chatId, attended, confirmedTotal)
        }
        // messageId == null → claim остаётся с сентинелом: итог не ретраится (best-effort by design)
    }

    /**
     * Включение тумблера: backfill — пост-статус для всех БУДУЩИХ событий клуба без живого
     * закрепа, чтобы организатор увидел фичу сразу, не дожидаясь следующего события.
     */
    @Transactional
    fun backfillForClub(clubId: UUID) {
        val link = liveLinkFor(clubId) ?: return
        eventRepository.findFutureEventsByClub(clubId, OffsetDateTime.now()).forEach { createPin(link, it) }
    }

    /**
     * Выключение тумблера: живые закрепы открепляются, их строки удаляются — повторное включение
     * создаст свежие пины. Сообщения остаются в истории чата (права удалять у бота нет и не просим).
     */
    @Transactional
    fun disableForClub(link: ChatLink) {
        pinRepository.findOpenByChatId(link.chatId).forEach { pin ->
            pin.messageId?.let { gateway.unpinChatMessage(pin.chatId, it) }
            pinRepository.delete(pin.eventId)
        }
        log.info("Live pins disabled: clubId={} chatId={}", link.clubId, link.chatId)
    }

    /**
     * Flush-планировщик: (1) перерисовать все dirty-закрепы, (2) закрыть закрепы стартовавших
     * событий. Период = дебаунс редактирования (см. класс-док).
     */
    @Scheduled(fixedDelayString = "\${chatlink.live-pin-flush-ms:30000}")
    @Transactional
    fun flush() {
        val batch = dirtyEventIds.toList()
        batch.forEach { eventId ->
            dirtyEventIds.remove(eventId)
            refreshPin(eventId)
        }
        pinRepository.findOpenStartedPins(OffsetDateTime.now()).forEach { pin ->
            eventRepository.findById(pin.eventId)?.let { closePin(pin, it) }
        }
    }

    /** TRUE = живой пост существует после попытки (уже был или только что создан). */
    private fun createPin(link: ChatLink, event: Event): Boolean {
        if (pinRepository.findByEventId(event.id) != null) return true
        val messageId = gateway.sendGroupMessageWithUrlButton(
            chatId = link.chatId,
            text = renderStatus(event),
            buttonText = renderer.buttonText(event),
            url = renderer.eventUrl(event.id),
            secondaryButton = mapsButton(event)
        )
        if (messageId == null) {
            // Пост не удался — строку не создаём: повторная попытка случится при следующем
            // включении тумблера/backfill, а здоровье чата организатор видит в табе «Чат».
            log.warn("Live pin post failed: eventId={} chatId={}", event.id, link.chatId)
            return false
        }
        pinRepository.insert(EventChatPin(event.id, link.chatId, messageId, closedAt = null, summaryMessageId = null))
        // Право закрепа могли и не выдать: пост уходит без pin, счётчики всё равно живут.
        if (link.canPinMessages) gateway.pinChatMessage(link.chatId, messageId)
        log.info("Live pin created: eventId={} chatId={} messageId={}", event.id, link.chatId, messageId)
        return true
    }

    private fun refreshPin(eventId: UUID) {
        val pin = pinRepository.findByEventId(eventId) ?: return
        val messageId = pin.messageId ?: return
        if (pin.closedAt != null) return
        val event = eventRepository.findById(eventId) ?: return
        // Стартовавшие/отменённые закроют close-проход и onEventCancelled — здесь не трогаем.
        if (!event.eventDatetime.isAfter(OffsetDateTime.now())) return
        gateway.editGroupMessage(
            chatId = pin.chatId,
            messageId = messageId,
            text = renderStatus(event),
            buttonText = renderer.buttonText(event),
            url = renderer.eventUrl(event.id),
            secondaryButton = mapsButton(event)
        )
    }

    /** Вторая кнопка закрепа — точка события в Яндекс.Картах; null у события без гео-точки. */
    private fun mapsButton(event: Event): Pair<String, String>? =
        event.yandexMapsUrl?.let { OPEN_IN_YANDEX_MAPS_BUTTON to it }

    private fun closePin(pin: EventChatPin, event: Event) {
        pin.messageId?.let { messageId ->
            val confirmed = eventResponseRepository.countConfirmed(event.id)
            gateway.editGroupMessage(pin.chatId, messageId, renderer.closedText(event, confirmed), null, null)
            gateway.unpinChatMessage(pin.chatId, messageId)
        }
        // Закрываем даже при сбое edit/unpin — иначе мёртвое сообщение ретраилось бы вечно.
        pinRepository.markClosed(event.id)
        log.info("Live pin closed (event started): eventId={} chatId={}", event.id, pin.chatId)
    }

    private fun renderStatus(event: Event): String =
        if (event.stage2Triggered) {
            renderer.stage2Text(
                event,
                confirmed = eventResponseRepository.countConfirmed(event.id),
                // Открытая встреча (V62): waitlist недостижим, а рендерер строку очереди не выводит —
                // не тратим COUNT-запрос на каждую перерисовку закрепа.
                waitlisted = if (event.isOpenEvent) 0 else eventResponseRepository.countWaitlisted(event.id)
            )
        } else {
            val counts = eventResponseRepository.countByVote(event.id)
            renderer.stage1Text(event, going = counts["going"] ?: 0, maybe = counts["maybe"] ?: 0)
        }

    /** Привязка клуба, если живой закреп включён и бот в чате; иначе null (фича молчит). */
    private fun liveLinkFor(clubId: UUID): ChatLink? =
        chatLinkRepository.findByClubId(clubId)
            ?.takeIf { it.livePinEnabled && it.botStatus.isInChat }
}
