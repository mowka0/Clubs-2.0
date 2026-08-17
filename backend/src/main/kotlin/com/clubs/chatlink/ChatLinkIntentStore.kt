package com.clubs.chatlink

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Зачем человек уходил добавлять бота в группу — «завести новый клуб» или «привязать чат к
 * клубу X».
 *
 * Нужно потому, что о добавлении бота Telegram сообщает апдейтом `my_chat_member`, а он несёт
 * только чат и того, кто добавил, — payload ссылки `?startgroup=<…>` в нём отсутствует.
 * Раньше намерение приходило командой `/start <payload>`, но Telegram перестал отправлять её
 * сам, когда в ссылке запрошены права администратора (`&admin=…`): клиент показывает экран
 * выбора прав, и payload на нём теряется. Человеку приходилось писать команду руками —
 * ровно то, от чего мы уходим (решение PO 2026-08-17).
 *
 * Намерение живёт в Redis, а не в БД: это след одного действия длиной в минуту, а таблица под
 * него потребовала бы миграции и уборки протухших строк.
 *
 * Redis необязателен: тесты поднимают контекст без него (`application-test.yml` исключает
 * автоконфигурацию), и требовать бин значило бы уронить весь контекст. Без Redis намерение
 * просто не запоминается, а бот отрабатывает дефолт «чат становится новым клубом».
 */
@Component
class ChatLinkIntentStore(
    redisProvider: ObjectProvider<StringRedisTemplate>
) {
    private val log = LoggerFactory.getLogger(ChatLinkIntentStore::class.java)
    private val redis: StringRedisTemplate? = redisProvider.getIfAvailable()

    /** Намерение человека, ушедшего добавлять бота в группу. */
    sealed interface Intent {
        /** Клуба ещё нет — создать его из выбранного чата. */
        data object NewClub : Intent

        /** Привязать чат к уже существующему клубу. */
        data class LinkExistingClub(val clubId: UUID) : Intent
    }

    fun remember(telegramId: Long, intent: Intent) {
        val storage = redis ?: return
        val value = when (intent) {
            is Intent.NewClub -> NEW_CLUB_VALUE
            is Intent.LinkExistingClub -> intent.clubId.toString()
        }
        runCatching { storage.opsForValue().set(key(telegramId), value, TTL) }
            .onFailure { log.warn("Chat-link intent not saved: telegramId={} error={}", telegramId, it.message) }
    }

    /**
     * Намерение и сразу его гашение: одно добавление бота — одно намерение. Иначе оставшийся
     * след увёл бы следующую группу, добавленную тем же человеком, не туда.
     *
     * null — человек добавил бота, не проходя через приложение (например, из меню Telegram).
     * Что делать в этом случае, решает вызывающий.
     */
    fun consume(telegramId: Long): Intent? {
        val storage = redis ?: return null
        val raw = runCatching { storage.opsForValue().getAndDelete(key(telegramId)) }
            .onFailure { log.warn("Chat-link intent not read: telegramId={} error={}", telegramId, it.message) }
            .getOrNull() ?: return null
        if (raw == NEW_CLUB_VALUE) return Intent.NewClub
        return runCatching { Intent.LinkExistingClub(UUID.fromString(raw)) }.getOrNull()
    }

    private fun key(telegramId: Long) = "$KEY_PREFIX$telegramId"

    companion object {
        /** Префикс ключа в Redis; дальше — telegram_id того, кто уходит добавлять бота. */
        private const val KEY_PREFIX = "chat-link-intent:"

        /** Значение намерения «клуба ещё нет». UUID клуба в этом месте невозможен, коллизии нет. */
        private const val NEW_CLUB_VALUE = "new"

        /**
         * Сколько живёт намерение. Пятнадцати минут хватает на выбор группы и экран прав даже с
         * отвлечением; дольше держать вредно — забытый след сработал бы на случайной группе.
         */
        private val TTL: Duration = Duration.ofMinutes(15)
    }
}
