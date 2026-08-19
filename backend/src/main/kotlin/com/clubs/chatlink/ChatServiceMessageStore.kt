package com.clubs.chatlink

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Служебная команда `/start@bot`, которую не удалось стереть сразу.
 *
 * Команду кладёт в группу клиент Telegram при добавлении бота, и бот стирает её сам — но только
 * если у него есть право «Удаление сообщений». Когда бота добавляет обычный участник, прав нет
 * вовсе (Telegram показывает экран прав лишь тому, кто может их выдать), и команда остаётся
 * висеть у всех на виду (баг PO 2026-08-19).
 *
 * Поэтому id такого сообщения запоминается: как только админ выдаст боту права, тот доудалит
 * его задним числом. Redis, а не БД: потеря записи означает лишь оставшийся мусор в чате.
 */
@Component
class ChatServiceMessageStore(
    redisProvider: ObjectProvider<StringRedisTemplate>
) {
    private val log = LoggerFactory.getLogger(ChatServiceMessageStore::class.java)
    private val redis: StringRedisTemplate? = redisProvider.getIfAvailable()

    fun remember(chatId: Long, messageId: Long) {
        val storage = redis ?: return
        runCatching { storage.opsForValue().set(key(chatId), messageId.toString(), TTL) }
            .onFailure { log.warn("Service message not saved: chatId={} error={}", chatId, it.message) }
    }

    /** Отложенное сообщение этого чата. null — стирать нечего. */
    fun peek(chatId: Long): Long? {
        val storage = redis ?: return null
        val raw = runCatching { storage.opsForValue().get(key(chatId)) }.getOrNull() ?: return null
        return raw.toLongOrNull()
    }

    /** Стёрли — запись больше не нужна. */
    fun forget(chatId: Long) {
        val storage = redis ?: return
        runCatching { storage.delete(key(chatId)) }
    }

    private fun key(chatId: Long) = "$KEY_PREFIX$chatId"

    companion object {
        private const val KEY_PREFIX = "chat-service-msg:"

        /**
         * Неделя: столько человек может ходить за администратором группы. Дольше держать незачем
         * — команда в ленте уже уехала вверх, и стирать её поздно.
         */
        private val TTL: Duration = Duration.ofDays(7)
    }
}
