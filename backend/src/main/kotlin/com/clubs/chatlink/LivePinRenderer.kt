package com.clubs.chatlink

import com.clubs.event.Event
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Тексты сообщений «живого закрепа» (мокап 03-live-pin-and-summary): статус Этапа 1 / Этапа 2,
 * финал при старте/отмене и пост-итог после явки. Только форматирование — данные приносит
 * [LivePinService]. Plain text, как все сообщения бота.
 */
@Component
class LivePinRenderer(
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    // Как в NotificationService: время события храним в UTC, читатель в чате не имеет
    // часового пояса устройства — рендерим МСК с явной пометкой.
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
        .withZone(ZoneId.of("Europe/Moscow"))

    /**
     * Url-кнопка для ГРУППЫ: WebApp-кнопки в группах запрещены Telegram, поэтому только
     * deep link Main Mini App `t.me/<bot>?startapp=event_…`. Требует включённого Mini App
     * у бота (BotFather → Bot Settings → Configure Mini App), иначе ссылка молча открывает
     * чат с ботом. Формат `t.me/<bot>/app?…` НЕ используется — он требует отдельной
     * регистрации short name через /newapp, которой у наших ботов нет (staging-баг:
     * «Bot App Not Found»). DeepLinkHandler фронта парсит `event_<uuid>` из startParam.
     */
    fun eventUrl(eventId: UUID): String = "https://t.me/$botUsername?startapp=event_$eventId"

    /** Кнопка под статусом: на Этапе 2 зовём подтверждать, до него — голосовать. */
    fun buttonText(event: Event): String =
        if (event.stage2Triggered) "Подтвердить участие" else "Проголосовать"

    /** Этап 1: набор — голоса и лимит мест. */
    fun stage1Text(event: Event, going: Int, maybe: Int): String =
        "📅 ${event.title}\n" +
            "🗓 ${event.eventDatetime.format(fmt)} · 📍 ${event.locationText}\n\n" +
            "✅ Идут — $going\n" +
            "🤔 Возможно — $maybe\n" +
            "👥 Мест — ${event.participantLimit}"

    /** Этап 2: гонка за места — подтверждённые, очередь, дедлайн (= старт события, граница окна на бэке). */
    fun stage2Text(event: Event, confirmed: Int, waitlisted: Int): String =
        "🏁 ${event.title} — подтверждение мест\n" +
            "🗓 ${event.eventDatetime.format(fmt)} · 📍 ${event.locationText}\n\n" +
            "✅ Подтвердили — $confirmed из ${event.participantLimit}\n" +
            "📋 В очереди — $waitlisted\n" +
            "⏳ Подтвердить до — ${event.eventDatetime.format(fmt)}"

    /** Финальный текст при старте события (закреп гаснет, итог придёт после отметки явки). */
    fun closedText(event: Event, confirmed: Int): String =
        "📅 ${event.title} · ${event.eventDatetime.format(fmt)}\n" +
            "Сбор закрыт — подтвердили ${confirmed} из ${event.participantLimit}. " +
            "Итог появится после отметки явки."

    /** Финальный текст при отмене события. */
    fun cancelledText(event: Event, reason: String?): String {
        val reasonLine = reason?.let { "\nПричина: $it" } ?: ""
        return "❌ ${event.title} · ${event.eventDatetime.format(fmt)} — событие отменено$reasonLine"
    }

    /**
     * Пост-итог после отметки явки (кадр C мокапа): номер встречи, «пришли X из Y»,
     * впервые пришедшие, следующее событие. [confirmedTotal] = 0 (организатор отметил явку без
     * Этапа 2) → рендерим просто «Пришли — X», без знаменателя.
     */
    fun summaryText(
        meetingNumber: Int,
        attended: Int,
        confirmedTotal: Int,
        firstTimerNames: List<String>,
        nextEvent: Event?
    ): String {
        val sb = StringBuilder("Встреча №$meetingNumber прошла ✅\n\n")
        sb.append(if (confirmedTotal > 0) "👥 Пришли — $attended из $confirmedTotal" else "👥 Пришли — $attended")
        firstTimersLine(firstTimerNames)?.let { sb.append("\n").append(it) }
        if (nextEvent != null) {
            sb.append("\n\nСледующая — ${nextEvent.eventDatetime.format(fmt)}: ${nextEvent.title}")
        }
        return sb.toString()
    }

    /**
     * «🎉 Наташа и Марк — впервые на встрече клуба»: имена в именительном падеже (склонять
     * русские имена безопасно нельзя), максимум [MAX_FIRST_TIMER_NAMES], дальше «и ещё k».
     */
    private fun firstTimersLine(names: List<String>): String? {
        if (names.isEmpty()) return null
        val shown = names.take(MAX_FIRST_TIMER_NAMES)
        val listed = when {
            names.size > MAX_FIRST_TIMER_NAMES ->
                shown.joinToString(", ") + " и ещё ${names.size - MAX_FIRST_TIMER_NAMES}"
            shown.size == 1 -> shown.first()
            else -> shown.dropLast(1).joinToString(", ") + " и ${shown.last()}"
        }
        return "🎉 $listed — впервые на встрече клуба"
    }

    companion object {
        // Сколько имён «впервые» показываем в итоге до схлопывания в «и ещё k»
        private const val MAX_FIRST_TIMER_NAMES = 3
    }
}
