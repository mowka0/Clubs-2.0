package com.clubs.bot

import com.clubs.event.Event
import com.clubs.event.EventResponseRepository
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
import java.time.ZoneId
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
    // Дата-время события хранится в UTC; DM не знает часовой пояс устройства читателя,
    // поэтому рендерим в московском времени с явной пометкой (сырой UTC для основной
    // русскоязычной аудитории расходится на 3 часа — в приложении время локальное для
    // устройства, и расхождение выглядело как неверное время события).
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
        .withZone(ZoneId.of("Europe/Moscow"))

    /**
     * Уведомляет участников клуба о создании нового события.
     * Отправляет сообщение в личку каждому активному участнику со ссылкой на Mini App.
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
        // Диплинк сразу на страницу события, чтобы кнопка открывала голосование, а не
        // общую домашнюю страницу приложения. React Router рендерит EventPage на /events/:id.
        val webAppPath = "/events/${event.id}"

        memberTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text, webAppPath = webAppPath, buttonText = "📅 Открыть событие")
        }
    }

    /**
     * Приглашает подтвердить участие при старте Этапа 2. Этап 2 открыт всем участникам клуба,
     * поэтому DM идёт going / maybe / НЕ ответившим на Этапе 1 (findStage2InviteTelegramIds).
     * Проголосовавшим "не иду" DM НЕ шлём — но подтвердить участие они всё равно смогут, если
     * передумают (Stage2Service.confirmParticipation открыт всем).
     */
    @Async
    fun sendStage2Started(event: Event) {
        val voterTelegramIds = eventResponseRepository.findStage2InviteTelegramIds(event.id)
        if (voterTelegramIds.isEmpty()) {
            log.info("Stage 2 DM SKIPPED — no eligible members for eventId={}", event.id)
            return
        }
        log.info("Stage 2 DM: eventId={} recipients={}", event.id, voterTelegramIds.size)
        val text = "⏰ Этап 2 начался!\n\n📌 ${event.title} — ${event.eventDatetime.format(fmt)}\n\nПодтвердите или откажитесь от участия в приложении:"
        val webAppPath = "/events/${event.id}"

        voterTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text, webAppPath = webAppPath, buttonText = "✅ Подтвердить участие")
        }
    }


    /**
     * F5-14: уведомляет ВСЕХ участников клуба с доступом об отмене события, с опциональной
     * причиной от организатора (UPDATED 2026-07-05: раньше — только going/maybe; теперь всем,
     * симметрично уведомлению о создании). Best-effort, как и все остальные DM.
     */
    @Async
    fun sendEventCancelled(event: Event, reason: String?) {
        // Об отмене сообщаем ВСЕМ участникам клуба с доступом (симметрично sendEventCreated:
        // кто узнал о создании — узнаёт и об отмене), а не только выразившим интерес.
        val recipientTelegramIds = membershipRepository.findMemberTelegramIds(event.clubId)
        if (recipientTelegramIds.isEmpty()) {
            log.info("Event-cancelled DM SKIPPED — no members with access for clubId={}", event.clubId)
            return
        }
        log.info("Event-cancelled DM: eventId={} clubId={} recipients={}", event.id, event.clubId, recipientTelegramIds.size)
        val reasonLine = reason?.let { "\n\nПричина: $it" } ?: ""
        val text = "❌ Событие отменено\n\n📌 ${event.title} — ${event.eventDatetime.format(fmt)}$reasonLine"
        val webAppPath = "/events/${event.id}"
        recipientTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text, webAppPath = webAppPath, buttonText = "📅 Открыть событие")
        }
    }

    /**
     * Напоминание Feature A (~за 2ч до события): подталкивает проголосовавших "иду"/"возможно",
     * кто ещё не подтвердил участие, сделать это до закрытия окна в момент начала события.
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
            "Подтвердите участие, иначе место займут другие:"
        val path = "/events/${event.id}"
        telegramIds.forEach { sendDm(it.toString(), text, webAppPath = path, buttonText = "✅ Подтвердить участие") }
    }

    /**
     * Напоминание Feature B (~через 24ч после события): подталкивает организатора отметить явку.
     * Пока он это не сделает, репутация по событию не финализируется (см. events.md, EXP-2).
     */
    @Async
    fun sendAttendanceReminder(event: Event, organizerTelegramId: Long) {
        log.info("Attendance reminder DM: eventId={} organizerTelegramId={}", event.id, organizerTelegramId)
        val text = "📋 Событие «${event.title}» (${event.eventDatetime.format(fmt)}) прошло.\n\n" +
            "Отметьте, кто пришёл — без этого репутация участников не начислится:"
        sendDm(organizerTelegramId.toString(), text, webAppPath = "/events/${event.id}", buttonText = "Отметить явку")
    }

    /**
     * Уведомляет участников, которые ТОЛЬКО ЧТО стали "отсутствовал" в этой отметке, предлагая
     * оспорить. Получатели передаются явно (F5-15.2), а не выбираются повторным запросом по
     * attendance=absent, чтобы повторная отметка не рассылала DM участникам, уже отмеченным
     * отсутствующими в предыдущий раз.
     */
    @Async
    fun sendAttendanceMarked(eventId: UUID, newlyAbsentUserIds: List<UUID>) {
        if (newlyAbsentUserIds.isEmpty()) return
        val absentTelegramIds = eventResponseRepository.findTelegramIdsByEventAndUserIds(eventId, newlyAbsentUserIds)
        val text = "📋 Организатор отметил присутствие на событии.\n\nВас отметили как отсутствующего. Если это ошибка — оспорьте на странице события:"
        // Диплинк сразу на страницу события, чтобы кнопка оспаривания была в один тап (ATT-3).
        val webAppPath = "/events/$eventId"

        absentTelegramIds.forEach { telegramId ->
            sendDm(telegramId.toString(), text, webAppPath = webAppPath, buttonText = "Оспорить явку")
        }
    }

    /**
     * ATT-3: участник оспорил отметку "не пришёл" — организатор должен разрешить спор до
     * закрытия окна оспаривания, иначе исходная отметка останется в силе и штраф применится.
     */
    @Async
    fun sendAttendanceDisputed(event: Event, organizerTelegramId: Long, disputerName: String) {
        log.info("Attendance-disputed DM: eventId={} organizerTelegramId={}", event.id, organizerTelegramId)
        val text = "⚖️ $disputerName оспорил отметку «не пришёл» на событии «${event.title}».\n\n" +
            "Разберите спор до закрытия окна оспаривания — иначе останется исходная отметка, и участник получит штраф:"
        sendDm(organizerTelegramId.toString(), text, webAppPath = "/events/${event.id}", buttonText = "Разобрать спор")
    }

    fun sendDirectMessage(telegramId: Long, text: String) {
        sendDm(telegramId.toString(), text, webAppPath = null, buttonText = DEFAULT_BUTTON_TEXT)
    }

    /**
     * Уведомляет организатора платного клуба о том, что участник заявил об оплате взноса
     * вне платформы (de-Stars) и ждёт подтверждения. Best-effort fire-and-forget как и другие
     * DM; диплинк на «Мои клубы», где список «Ждут оплаты» даёт организатору подтвердить
     * или отклонить.
     */
    @Async
    fun sendDuesClaimedDM(organizerTelegramId: Long, memberDisplayName: String, clubName: String, method: String) {
        val methodLabel = if (method == "cash") "наличными" else "по СБП"
        val text = "💸 $memberDisplayName оплатил(а) вступление $methodLabel в клуб «$clubName» и ждёт вашего решения."
        sendDm(
            chatId = organizerTelegramId.toString(),
            text = text,
            webAppPath = "/my-clubs",
            buttonText = "Проверить оплату"
        )
    }

    /**
     * Уведомляет организатора клуба о том, что подана новая заявка.
     * Fire-and-forget: любая ошибка Telegram логируется в [sendDm], но НЕ
     * пробрасывается вызывающему коду (поэтому исходная транзакция БД никогда
     * не откатывается). Spring проксирует @Async, поэтому вызов возвращается сразу.
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
     * Уведомляет заявителя о том, что организатор одобрил его заявку на вступление. Для ПЛАТНОГО
     * клуба одобрение переводит его в `frozen` — DM подталкивает оплатить взнос, чтобы открыть
     * доступ; для БЕСПЛАТНОГО клуба он уже внутри, поэтому это просто приветствие. Best-effort
     * fire-and-forget; диплинк на страницу клуба (где frozen-участник нажимает «Оплатить взнос»).
     */
    @Async
    fun sendApplicationApprovedDM(applicantTelegramId: Long, clubName: String, clubId: UUID, paid: Boolean) {
        val text: String
        val button: String
        if (paid) {
            text = "✅ Вашу заявку в клуб «$clubName» одобрили — оплатите вступление, чтобы получить доступ."
            button = "Оплатить взнос"
        } else {
            text = "✅ Вашу заявку в клуб «$clubName» одобрили. Добро пожаловать!"
            button = "Открыть клуб"
        }
        sendDm(applicantTelegramId.toString(), text, webAppPath = "/clubs/$clubId", buttonText = button)
    }

    /**
     * Best-effort DM платному участнику, которому организатор только что закрыл доступ
     * («Закрыть доступ» → frozen). Диплинк на страницу клуба, где frozen-участник нажимает
     * «Оплатить взнос», чтобы заявить об оплате и вернуть доступ. Fire-and-forget — никогда
     * не блокирует действие заморозки.
     */
    @Async
    fun sendAccessFrozenDM(memberTelegramId: Long, clubName: String, clubId: UUID) {
        val text = "🔒 Организатор клуба «$clubName» закрыл вам доступ. Чтобы вернуть его, оплатите взнос."
        sendDm(memberTelegramId.toString(), text, webAppPath = "/clubs/$clubId", buttonText = "Оплатить взнос")
    }

    /**
     * DM с инлайн-кнопкой-диплинком, открывающей Mini App на конкретном
     * route. [webAppPath] — путь с префиксом-слэшем, например "/skladchina/<id>"
     * или "/events/<id>". React Router фронтенда рендерит нужную страницу
     * напрямую — переход через DeepLinkHandler не нужен.
     *
     * Замечание по реализации: кнопка использует WebAppInfo (не t.me URL), потому что
     * Telegram блокирует self-bot t.me-ссылки внутри DM с тем же самым ботом
     * (циклическое взаимодействие). WebAppInfo надёжно открывает Mini App.
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
        // Fallback — обычный текст без inline-кнопки.
        try {
            val msg = SendMessage.builder().chatId(chatId).text(text).build()
            telegramClient.execute(msg)
            log.info("DM sent without inline button (fallback): chatId={}", chatId)
        } catch (e: Exception) {
            log.error("Failed to send fallback DM to chat {}: {} ({})", chatId, e.message, e.javaClass.simpleName, e)
        }
    }

    private fun buildKeyboard(buttonText: String, webAppPath: String?): InlineKeyboardMarkup {
        // WebApp-кнопка с URL фронтенда — открывает Mini App напрямую на нужном
        // route (через React Router). URL-кнопка вида t.me/<bot>/... НЕ используется,
        // потому что Telegram блокирует self-bot ссылки внутри DM с этим же ботом.
        val url = if (webAppPath != null) "$webAppBaseUrl$webAppPath" else webAppBaseUrl
        val button = InlineKeyboardButton.builder()
            .text(buttonText)
            .webApp(WebAppInfo(url))
            .build()
        return InlineKeyboardMarkup(listOf(InlineKeyboardRow(button)))
    }

    companion object {
        // Текст кнопки по умолчанию для DM без явно заданного текста кнопки
        private const val DEFAULT_BUTTON_TEXT = "📱 Открыть Clubs"
    }
}
