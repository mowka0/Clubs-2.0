package com.clubs.bot

import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.tables.records.EventsRecord
import com.clubs.generated.jooq.tables.references.EVENT_RESPONSES
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import org.jooq.DSLContext
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
    private val dsl: DSLContext,
    private val telegramClient: TelegramClient
) {

    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Notify club members when a new event is created.
     * Sends a message to each active member's DM with a link to the Mini App.
     */
    @Async
    fun sendEventCreated(event: EventsRecord) {
        val memberTelegramIds = getMemberTelegramIds(event.clubId!!)
        val dateStr = event.eventDatetime?.format(fmt) ?: "TBD"
        val text = "🆕 Новое событие в клубе!\n\n📌 ${event.title}\n📍 ${event.locationText}\n🗓 $dateStr\n👥 Лимит: ${event.participantLimit}\n\nГолосуйте в приложении:"

        memberTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    /**
     * Notify going/maybe voters when Stage 2 starts, asking them to confirm.
     */
    @Async
    fun sendStage2Started(event: EventsRecord) {
        val voterTelegramIds = getGoingVoterTelegramIds(event.id!!)
        val text = "⏰ Этап 2 начался!\n\n📌 ${event.title} — ${event.eventDatetime?.format(fmt)}\n\nПодтвердите или откажитесь от участия в приложении:"

        voterTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    /**
     * Notify absent members after attendance is marked, offering dispute option.
     */
    @Async
    fun sendAttendanceMarked(eventId: UUID) {
        val absentTelegramIds = getAbsentMemberTelegramIds(eventId)
        val text = "📋 Организатор отметил присутствие на событии.\n\nВас отметили как отсутствующего. Если это ошибка — оспорьте в приложении:"

        absentTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text)
        }
    }

    fun sendDirectMessage(telegramId: Long, text: String) {
        sendDm(telegramId.toString(), text)
    }

    private fun sendDm(chatId: String, text: String) {
        try {
            val button = InlineKeyboardButton.builder()
                .text("📱 Открыть Clubs")
                .webApp(WebAppInfo("https://t.me/clubs_v2_bot/app"))
                .build()
            val markup = InlineKeyboardMarkup(listOf(InlineKeyboardRow(button)))
            val msg = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build()
            telegramClient.execute(msg)
        } catch (e: Exception) {
            log.warn("Failed to send DM to chat {}: {}", chatId, e.message)
        }
    }

    private fun getMemberTelegramIds(clubId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    private fun getGoingVoterTelegramIds(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(EVENT_RESPONSES.EVENT_ID.eq(eventId))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()

    private fun getAbsentMemberTelegramIds(eventId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(EVENT_RESPONSES)
            .join(USERS).on(USERS.ID.eq(EVENT_RESPONSES.USER_ID))
            .where(
                EVENT_RESPONSES.EVENT_ID.eq(eventId)
                    .and(EVENT_RESPONSES.FINAL_STATUS.eq(FinalStatus.declined))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
}
