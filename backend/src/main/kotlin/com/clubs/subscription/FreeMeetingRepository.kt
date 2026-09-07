package com.clubs.subscription

import java.util.UUID

/**
 * Одна бесплатная встреча на чат Telegram (таблица chat_free_meeting, platform-billing.md R5/R6).
 * Признак живёт по chat_id отдельно от привязки: строка club_chat_links удаляется при отвязке,
 * а бесплатная встреча должна пережить отвязку, удаление клуба и повторное подключение чата.
 */
interface FreeMeetingRepository {

    /**
     * Атомарно берёт бесплатную встречу чата: true — взята (первая или возвращённая отменой),
     * false — уже использована. Две одновременные попытки сериализуются на первичном ключе.
     */
    fun claim(chatId: Long, clubId: UUID, eventId: UUID): Boolean

    /** Бесплатная встреча чата уже взята и не возвращена отменой. */
    fun isUsed(chatId: Long): Boolean

    /** Отмена встречи до старта возвращает бесплатную чату (R5). Число затронутых строк: 0 = встреча не была бесплатной. */
    fun release(eventId: UUID): Int

    /**
     * Переезд группы в супергруппу: признак едет за новым chat_id. Если у нового id уже есть своя
     * строка (клуб-двойник успел взять бесплатную), старая просто отбрасывается — чат один.
     */
    fun migrateChatId(oldChatId: Long, newChatId: Long): Int
}
