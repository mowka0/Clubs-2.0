package com.clubs.subscription

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Бесплатный период чата Telegram (таблица chat_trial, platform-billing.md R4–R6): отсчёт идёт с
 * первой созданной встречи. Признак живёт по chat_id отдельно от привязки — строка
 * club_chat_links удаляется при отвязке, а срок должен пережить отвязку, удаление клуба и
 * повторное подключение того же чата новым клубом.
 */
interface ChatTrialRepository {

    /**
     * Момент старта бесплатного периода чата: у первой встречи он заводится, у последующих
     * возвращается уже записанный. Вставка идемпотентна — две одновременные первые встречи
     * сериализуются на первичном ключе и получают один и тот же старт.
     */
    fun startOrGet(chatId: Long, clubId: UUID, eventId: UUID): TrialStart

    /** Когда у чата начался бесплатный период; null — встреч ещё не создавали. */
    fun findStartedAt(chatId: Long): OffsetDateTime?

    /**
     * Чаты, у которых бесплатный период кончится не позже [until] и подписки ещё не было —
     * кандидаты на DM о конце. Возвращает и порог последнего отправленного напоминания.
     */
    fun findTrialsEndingBefore(until: OffsetDateTime, trialDays: Long): List<ChatTrial>

    /** Запоминает отправленный порог напоминания (3 или 1 день), чтобы DM не повторялся на каждом тике. */
    fun markReminded(chatId: Long, daysLeft: Int): Int

    /**
     * Переезд группы в супергруппу: срок едет за новым chat_id. Если у нового id уже есть своя
     * строка (клуб-двойник успел создать встречу), старая просто отбрасывается — чат один.
     */
    fun migrateChatId(oldChatId: Long, newChatId: Long): Int
}

/** Начало бесплатного периода: когда пошёл отсчёт и была ли это первая встреча чата. */
data class TrialStart(val startedAt: OffsetDateTime, val justStarted: Boolean)

/** Строка бесплатного периода чата для планировщика напоминаний. */
data class ChatTrial(
    val chatId: Long,
    val clubId: UUID,
    val startedAt: OffsetDateTime,
    /** Порог последнего отправленного DM в днях до конца: 3, 1 или null (не напоминали). */
    val reminderDaysLeft: Int?,
)
