package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class NotificationService(
    private val membershipRepository: MembershipRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val telegramClient: TelegramClient
) {

    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Notify club members when a new event is created.
     * Sends a message to each active member's DM with a link to the Mini App.
     */
    @Async
    fun sendEventCreated(event: Event) {
        val memberTelegramIds = membershipRepository.findMemberTelegramIds(event.clubId)
        val dateStr = event.eventDatetime.format(fmt)
        val text = "🆕 Новое событие в клубе!\n\n📌 ${event.title}\n📍 ${event.locationText}\n🗓 $dateStr\n👥 Лимит: ${event.participantLimit}\n\nГолосуйте в приложении:"

        memberTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    /**
     * Notify going/maybe voters when Stage 2 starts, asking them to confirm.
     */
    @Async
    fun sendStage2Started(event: Event) {
        val voterTelegramIds = eventResponseRepository.findResponderTelegramIdsByEventId(event.id)
        val text = "⏰ Этап 2 начался!\n\n📌 ${event.title} — ${event.eventDatetime.format(fmt)}\n\nПодтвердите или откажитесь от участия в приложении:"

        voterTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    /**
     * Notify absent members after attendance is marked, offering dispute option.
     */
    @Async
    fun sendAttendanceMarked(eventId: UUID) {
        val absentTelegramIds = eventResponseRepository.findTelegramIdsByEventAndAttendance(eventId, AttendanceStatus.absent)
        val text = "📋 Организатор отметил присутствие на событии.\n\nВас отметили как отсутствующего. Если это ошибка — оспорьте в приложении:"

        absentTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    fun sendDirectMessage(telegramId: Long, text: String) {
        sendDm(telegramId.toString(), text, startApp = null, buttonText = DEFAULT_BUTTON_TEXT)
    }

    /**
     * DM with a deep-link inline button into the Mini App.
     * [startApp] becomes the `?startapp=<value>` parameter on the t.me URL,
     * which DeepLinkHandler reads from `initData.start_param` and routes to
     * e.g. /skladchina/{id} or /events/{id}.
     */
    fun sendDirectMessageWithDeepLink(
        telegramId: Long,
        text: String,
        startApp: String,
        buttonText: String = DEFAULT_BUTTON_TEXT
    ) {
        sendDm(telegramId.toString(), text, startApp, buttonText)
    }

    private fun sendDm(
        chatId: String,
        text: String,
        startApp: String? = null,
        buttonText: String = DEFAULT_BUTTON_TEXT
    ) {
        log.info("Sending DM to chatId={} startApp={}", chatId, startApp)
        try {
            val markup = buildKeyboard(buttonText, startApp)
            val msg = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build()
            telegramClient.execute(msg)
            log.info("DM sent with inline button: chatId={}", chatId)
            return
        } catch (e: Exception) {
            log.error("Failed to send DM with inline button to chat {}: {} ({})", chatId, e.message, e.javaClass.simpleName, e)
        }
        // Fallback — plain text без inline button.
        try {
            val msg = SendMessage.builder().chatId(chatId).text(text).build()
            telegramClient.execute(msg)
            log.info("DM sent without inline button (fallback): chatId={}", chatId)
        } catch (e: Exception) {
            log.error("Failed to send fallback DM to chat {}: {} ({})", chatId, e.message, e.javaClass.simpleName, e)
        }
    }

    private fun buildKeyboard(buttonText: String, startApp: String?): InlineKeyboardMarkup {
        // С startApp — t.me deep-link (Telegram пробрасывает startapp в initData.start_param).
        // Без — обычная WebApp кнопка (открывает Mini App на главный route).
        val button = if (startApp != null) {
            InlineKeyboardButton.builder()
                .text(buttonText)
                .url("https://t.me/$BOT_USERNAME/$WEBAPP_SLUG?startapp=$startApp")
                .build()
        } else {
            InlineKeyboardButton.builder()
                .text(buttonText)
                .webApp(WebAppInfo("https://t.me/$BOT_USERNAME/$WEBAPP_SLUG"))
                .build()
        }
        return InlineKeyboardMarkup(listOf(InlineKeyboardRow(button)))
    }

    companion object {
        private const val BOT_USERNAME = "clubs_v2_bot"
        private const val WEBAPP_SLUG = "app"
        private const val DEFAULT_BUTTON_TEXT = "📱 Открыть Clubs"
    }
}
