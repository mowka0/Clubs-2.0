package com.clubs.feedback

import com.clubs.bot.NotificationService
import com.clubs.common.exception.FeedbackDeliveryException
import com.clubs.common.exception.NotFoundException
import com.clubs.feedback.dto.SubmitFeedbackRequest
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FeedbackService(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    // Username саппорт-аккаунта (без @): DM с баг-репортами уходит пользователю с этим
    // telegram_username. Bot API не умеет слать по @username и не даёт боту писать первым,
    // поэтому аккаунт резолвится через users — он должен хотя бы раз открыть Mini App
    // этого окружения (см. docs/modules/feedback.md).
    @Value("\${telegram.support-username}") private val supportUsername: String,
    // Числовой telegram chat id саппорта. Если задан — резолв по users не нужен, и канал
    // не перехватывается сменой/освобождением username (security-ревью: mutable username
    // как единственный резолвер = риск угона баг-репортов). Пусто = резолв по username.
    @Value("\${telegram.support-chat-id}") private val supportChatId: String,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun submitFeedback(userId: UUID, request: SubmitFeedbackRequest) {
        val reporter = userRepository.findById(userId)
            ?: throw NotFoundException("User $userId not found")
        val chatId = resolveSupportChatId()

        // Отправка синхронная и без фолбэков: 204 означает «сообщение реально в Telegram».
        // Молчаливо потерянный баг-репорт хуже честной 503.
        val sent = notificationService.trySendDirectMessage(chatId, buildReport(reporter, request))
        if (!sent) throw FeedbackDeliveryException("Telegram delivery failed")
        log.info("Feedback delivered: userId={} supportChatId={} length={}", userId, chatId, request.message.length)
    }

    private fun resolveSupportChatId(): Long {
        supportChatId.toLongOrNull()?.let { return it }
        if (supportChatId.isNotBlank()) {
            log.warn("TELEGRAM_SUPPORT_CHAT_ID is set but not numeric ('{}') — falling back to username lookup", supportChatId)
        }
        val support = userRepository.findByTelegramUsername(supportUsername)
        if (support == null) {
            log.error("Feedback undeliverable: support user @{} not found in users", supportUsername)
            throw FeedbackDeliveryException("Support account is not available")
        }
        return support.telegramId ?: throw FeedbackDeliveryException("Support account has no telegram id")
    }

    /** Plain text без parse mode — пользовательский ввод не должен интерпретироваться как разметка. */
    private fun buildReport(reporter: UsersRecord, request: SubmitFeedbackRequest): String {
        val name = listOfNotNull(reporter.firstName, reporter.lastName).joinToString(" ")
        val username = reporter.telegramUsername?.let { "@$it" } ?: "без username"
        return buildString {
            appendLine("🐞 Обратная связь")
            appendLine()
            appendLine(request.message.trim())
            appendLine()
            appendLine("— $name ($username), tg id ${reporter.telegramId}")
            // page — route, не свободный текст: срезаем управляющие символы, иначе перевод строки
            // позволял бы дописать «второй репорт» после подлинной подписи (находка ревью).
            request.page?.replace(Regex("\\p{Cntrl}"), " ")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { append("— Экран: $it") }
        }.trimEnd()
    }
}
