package com.clubs.chatlink

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сообщения бота в привязанном чате по конкретному событию («живой закреп», слайс 3):
 * закреплённый пост-статус, который бот редактирует, и пост-итог после отметки явки.
 * Одна строка на событие (event_chat_pins).
 */
data class EventChatPin(
    val eventId: UUID,
    /** Чат на момент поста — снимок: при перепривязке чата старые строки не переезжают. */
    val chatId: Long,
    /** Id сообщения-статуса (NULL = пост не создавался или не удался). */
    val messageId: Long?,
    /** NULL = закреп живой (редактируется); NOT NULL = закрыт (старт/отмена/тумблер выключен). */
    val closedAt: OffsetDateTime?,
    /** Id поста-итога «Встреча №N прошла» (NULL = итог не постился — гейт от дублей). */
    val summaryMessageId: Long?
)

interface EventChatPinRepository {
    fun findByEventId(eventId: UUID): EventChatPin?
    fun insert(pin: EventChatPin): EventChatPin

    /** Живые закрепы уже стартовавших событий — close-проход flush-планировщика. */
    fun findOpenStartedPins(now: OffsetDateTime): List<EventChatPin>

    /** Живые закрепы чата — выключение тумблера открепляет и удаляет их. */
    fun findOpenByChatId(chatId: Long): List<EventChatPin>

    fun markClosed(eventId: UUID)
    fun delete(eventId: UUID)

    /**
     * Атомарно «занять» право на пост-итог: TRUE ровно один раз для события
     * (summary_message_id проставляется сентинелом 0 только если был NULL; строки нет — создаётся).
     * Дедуп при конкурентных отметках явки: проигравший получает FALSE и итог не постит.
     */
    fun tryClaimSummary(eventId: UUID, chatId: Long): Boolean

    /** Зафиксировать id реально отправленного поста-итога (после успешного claim + send). */
    fun setSummaryMessageId(eventId: UUID, summaryMessageId: Long)
}
