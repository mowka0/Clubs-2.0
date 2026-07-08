package com.clubs.chatlink

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Сообщение-статус бота в привязанном чате по конкретной складчине («живой статус сбора»,
 * слайс 3.5): прогресс «Скинулись N из M», дедлайн и упоминания ещё не ответивших.
 * Одна строка на складчину (skladchina_chat_posts); поста-итога нет — финал это последний edit.
 */
data class SkladchinaChatPost(
    val skladchinaId: UUID,
    /** Чат на момент поста — снимок: при перепривязке чата старые строки не переезжают. */
    val chatId: Long,
    /** Id сообщения-статуса. Не-null: строка создаётся только после успешной отправки поста. */
    val messageId: Long,
    /** NULL = статус живой (редактируется); NOT NULL = закрыт (складчина закрыта/тумблер выключен). */
    val closedAt: OffsetDateTime?
)

interface SkladchinaChatPostRepository {
    fun findBySkladchinaId(skladchinaId: UUID): SkladchinaChatPost?

    /**
     * Вставка «только если строки ещё нет» (ON CONFLICT DO NOTHING): гонка
     * backfill × onSkladchinaCreated не должна ронять транзакцию тумблера на PK-конфликте.
     * FALSE = строку уже вставил конкурент — вызывающий пропускает pin.
     */
    fun insertIfAbsent(post: SkladchinaChatPost): Boolean

    /** Живые статусы чата — выключение тумблера открепляет и удаляет их. */
    fun findOpenByChatId(chatId: Long): List<SkladchinaChatPost>

    /**
     * Живые статусы складчин, которые уже НЕ активны — close-проход flush-планировщика.
     * Обязателен для корректности, а не страховка: каскады cancelActiveByClub (удаление клуба)
     * и cancelActiveByEventId (отмена события split_bill) минуют closeInternal и не публикуют
     * SkladchinaClosedEvent — без прохода по БД такие посты остались бы «живыми» навсегда.
     */
    fun findOpenPostsOfInactiveSkladchinas(): List<SkladchinaChatPost>

    fun markClosed(skladchinaId: UUID)
    fun delete(skladchinaId: UUID)
}
