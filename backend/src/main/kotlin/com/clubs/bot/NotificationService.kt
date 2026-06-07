package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.EventResponseRepository
import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.membership.MembershipRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val telegramClient: TelegramClient,
    @Value("\${telegram.bot-username}") private val botUsername: String,
    @Value("\${telegram.webapp-base-url}") private val webAppBaseUrl: String
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
        if (memberTelegramIds.isEmpty()) {
            log.warn("Event-created DM SKIPPED — no members with access for clubId={}", event.clubId)
            return
        }
        log.info("Event-created DM: eventId={} clubId={} recipients={}", event.id, event.clubId, memberTelegramIds.size)
        val dateStr = event.eventDatetime.format(fmt)
        val text = "🆕 Новое событие в клубе!\n\n📌 ${event.title}\n📍 ${event.locationText}\n🗓 $dateStr\n👥 Лимит: ${event.participantLimit}\n\nГолосуйте в приложении:"
        // Deep-link straight to the event page so the button opens voting, not the
        // generic app home. React Router renders EventPage at /events/:id.
        val webAppPath = "/events/${event.id}"

        memberTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text, webAppPath = webAppPath, buttonText = "📅 Открыть событие")
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
     * Feature A reminder (~2h before the event): nudge going/maybe voters who have NOT
     * confirmed yet to confirm before the window closes at event start.
     */
    @Async
    fun sendConfirmReminder(event: Event) {
        val telegramIds = eventResponseRepository.findUnconfirmedVoterTelegramIds(event.id)
        if (telegramIds.isEmpty()) {
            log.info("Confirm reminder SKIPPED — no unconfirmed voters for eventId={}", event.id)
            return
        }
        log.info("Confirm reminder DM: eventId={} recipients={}", event.id, telegramIds.size)
        val text = "⏰ Скоро начало: «${event.title}» — ${event.eventDatetime.format(fmt)}.\n\n" +
            "Подтвердите участие, иначе место освободится:"
        val path = "/events/${event.id}"
        telegramIds.forEach { sendDm(it.toString(), text, webAppPath = path, buttonText = "✅ Подтвердить участие") }
    }

    /**
     * Feature B reminder (~24h after the event): nudge the organizer to mark attendance.
     * Until they mark it, reputation is never finalized for the event (see events.md, EXP-2).
     */
    @Async
    fun sendAttendanceReminder(event: Event, organizerTelegramId: Long) {
        log.info("Attendance reminder DM: eventId={} organizerTelegramId={}", event.id, organizerTelegramId)
        val text = "📋 Событие «${event.title}» (${event.eventDatetime.format(fmt)}) прошло.\n\n" +
            "Отметьте, кто пришёл — без этого репутация участников не начислится:"
        sendDm(organizerTelegramId.toString(), text, webAppPath = "/events/${event.id}", buttonText = "Отметить явку")
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
        sendDm(telegramId.toString(), text, webAppPath = null, buttonText = DEFAULT_BUTTON_TEXT)
    }

    /**
     * Notify a club organizer that a new application has been submitted.
     * Fire-and-forget: any Telegram error is logged in [sendDm] but does NOT
     * propagate to the caller (so the originating DB transaction is never
     * rolled back). Spring proxies @Async, so the call returns immediately.
     */
    @Async
    fun sendApplicationCreatedDM(
        organizerTelegramId: Long,
        applicantDisplayName: String,
        clubName: String
    ) {
        val text = "📥 Новая заявка от $applicantDisplayName в клуб «$clubName»"
        sendDm(
            chatId = organizerTelegramId.toString(),
            text = text,
            webAppPath = "/my-clubs?focus=inbox",
            buttonText = "Открыть заявки"
        )
    }

    /**
     * DM with a deep-link inline button that opens the Mini App on a specific
     * route. [webAppPath] is path-prefixed-with-slash, e.g. "/skladchina/<id>"
     * or "/events/<id>". Frontend's React Router renders the matching page
     * directly — no DeepLinkHandler hop needed.
     *
     * Implementation note: button uses WebAppInfo (not t.me URL) because
     * Telegram blocks self-bot t.me links inside DMs with that same bot
     * (cyclic interaction). WebAppInfo opens the Mini App reliably.
     */
    fun sendDirectMessageWithDeepLink(
        telegramId: Long,
        text: String,
        webAppPath: String,
        buttonText: String = DEFAULT_BUTTON_TEXT
    ) {
        sendDm(telegramId.toString(), text, webAppPath, buttonText)
    }

    private fun sendDm(
        chatId: String,
        text: String,
        webAppPath: String? = null,
        buttonText: String = DEFAULT_BUTTON_TEXT
    ) {
        log.info("Sending DM to chatId={} webAppPath={}", chatId, webAppPath)
        try {
            val markup = buildKeyboard(buttonText, webAppPath = webAppPath)
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

    private fun buildKeyboard(buttonText: String, webAppPath: String?): InlineKeyboardMarkup {
        // WebApp button with frontend URL — открывает Mini App напрямую на нужном
        // route (через React Router). t.me/<bot>/... URL button НЕ используется,
        // потому что Telegram блокирует self-bot ссылки внутри DM с этим же ботом.
        val url = if (webAppPath != null) "$webAppBaseUrl$webAppPath" else webAppBaseUrl
        val button = InlineKeyboardButton.builder()
            .text(buttonText)
            .webApp(WebAppInfo(url))
            .build()
        return InlineKeyboardMarkup(listOf(InlineKeyboardRow(button)))
    }

    companion object {
        private const val DEFAULT_BUTTON_TEXT = "📱 Открыть Clubs"
    }
}
