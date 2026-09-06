package com.clubs.chatlink

import com.clubs.event.Event
import com.clubs.event.EventMessageTemplate
import com.clubs.event.EventEditedEvent
import com.clubs.event.locationDisplay
import com.clubs.event.locationDisplayOrDash
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Тексты сообщений «живого закрепа» (мокап 03-live-pin-and-summary): статус Этапа 1 / Этапа 2,
 * финал при старте/отмене и пост-итог после явки. Только форматирование — данные приносит
 * [LivePinService]. HTML parse_mode — как и DM, тексты идут по общему шаблону
 * [EventMessageTemplate] с жирным заголовком формата встречи; пользовательский ввод
 * экранируется там же.
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

    /**
     * Кнопка под статусом: на Этапе 2 зовём подтверждать, до него — голосовать. У встречи с
     * порогом набора (V83) подтверждать нечего — место даёт голос, поэтому после закрытия состава
     * кнопка просто открывает встречу.
     */
    fun buttonText(event: Event): String = when {
        event.isRosterEvent && event.stage2Triggered -> "Открыть встречу"
        event.stage2Triggered -> "Подтвердить участие"
        else -> "Проголосовать"
    }

    /**
     * Идёт набор состава (формат 🎟): «собрались N из M — нужно ещё K» и дедлайн набора.
     * Для продукта-плагина к чату это главный эффект фичи: недобор виден всему чату, а не
     * только организатору.
     */
    fun rosterText(event: Event, confirmed: Int, deadline: OffsetDateTime): String =
        "${EventMessageTemplate.head(event, fmt)}\n\n" +
            "$VOTE_CALL_TO_ACTION\n" +
            EventMessageTemplate.rosterStats(event, confirmed, deadline, fmt)

    /** Состав собран — призыва голосовать больше нет, набор закрыт. */
    fun rosterClosedText(event: Event, confirmed: Int, waitlisted: Int): String =
        "${EventMessageTemplate.head(event, fmt)}\n\n" +
            EventMessageTemplate.rosterClosedStats(event, confirmed, waitlisted)

    /**
     * Этап 1 (идёт голосование) — общий шаблон встречи: формат жирным, что/когда/где, призыв
     * проголосовать и голоса.
     */
    fun stage1Text(event: Event, going: Int, maybe: Int): String =
        "${EventMessageTemplate.head(event, fmt)}\n\n" +
            "$VOTE_CALL_TO_ACTION\n" +
            EventMessageTemplate.stage1Stats(event, going, maybe)

    /**
     * Этап 2 (гонка за места) — тот же шаблон, но со счётчиком подтверждений и дедлайном
     * (= старт события, граница окна на бэке).
     */
    fun stage2Text(event: Event, confirmed: Int, waitlisted: Int): String =
        "${EventMessageTemplate.head(event, fmt)}\n\n" +
            EventMessageTemplate.stage2Stats(event, confirmed, waitlisted, fmt)

    /**
     * Финальный текст при старте события (закреп гаснет, итог придёт после отметки явки).
     * Не «Сбор закрыт» — слово «сбор» у нас занято складчиной и путало (фидбек PO 2026-07-08).
     */
    fun closedText(event: Event, confirmed: Int): String =
        "<b>${esc(EventMessageTemplate.formatName(event))} началась</b>\n\n" +
            "${esc(event.title)}\n" +
            "когда: ${event.eventDatetime.format(fmt)}\n\n" +
            "✅ Подтвердили — " +
            (event.participantLimit?.let { "$confirmed из $it" } ?: "$confirmed") +
            "\nИтог появится после отметки явки."

    /**
     * Громкий пост о правке встречи (только Этап 1): «было → стало» по тому, что реально
     * изменилось. Три формулировки вместо одной общей — участник должен понять суть из первой
     * строки, не вчитываясь: сдвиг времени и переезд требуют разных действий.
     * [edited] несёт и новое состояние, и прежнее.
     */
    fun editedText(edited: EventEditedEvent): String {
        val event = edited.event
        val old = edited.oldEvent
        val what = when {
            edited.isDatetimeChanged && edited.isLocationChanged -> "изменилась"
            edited.isLocationChanged -> "меняет место"
            else -> "перенесена"
        }
        val sb = StringBuilder("<b>${esc(EventMessageTemplate.formatName(event))} $what</b>\n\n")
        sb.append(esc(event.title)).append("\n")
        if (edited.isDatetimeChanged) {
            sb.append("\nкогда было: ${old.eventDatetime.format(fmt)}\n")
            sb.append("когда стало: ${event.eventDatetime.format(fmt)}\n")
        } else {
            sb.append("когда: ${event.eventDatetime.format(fmt)}\n")
        }
        if (edited.isLocationChanged) {
            // Пустая строка между «было» и «стало»: адреса длинные и переносятся на две-три
            // строки, слипшись они читаются как один абзац и разница теряется (правка PO).
            sb.append("\nгде было: ${esc(old.locationDisplayOrDash)}\n\n")
            sb.append("где стало: ${esc(event.locationDisplayOrDash)}")
        } else {
            event.locationDisplay?.let { sb.append("где: ${esc(it)}") }
        }
        // Правка — повод переголосовать: у кого поменялись планы из-за новой даты или места,
        // должен сказать об этом сейчас, а не молча не прийти. Пост живёт в общей ленте чата,
        // где кнопка читается как оформление, поэтому просим ещё и текстом.
        sb.append("\n\n").append(VOTE_CALL_TO_ACTION)
        return sb.toString().trimEnd()
    }

    /** Финальный текст при отмене события. */
    fun cancelledText(event: Event, reason: String?): String {
        val reasonLine = reason?.let { "\nпричина: ${esc(it)}" } ?: ""
        return "<b>${esc(EventMessageTemplate.formatName(event))} отменена</b>\n\n" +
            "${esc(event.title)}\n" +
            "когда: ${event.eventDatetime.format(fmt)}$reasonLine"
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

    /** Короткий алиас общего экранирования — тексты закрепа идут с HTML parse_mode. */
    private fun esc(s: String): String = EventMessageTemplate.escapeHtml(s)

    companion object {
        // Сколько имён «впервые» показываем в итоге до схлопывания в «и ещё k»
        private const val MAX_FIRST_TIMER_NAMES = 3

        // Призыв к действию над счётчиками Этапа 1 (PO 2026-08-10): пост живёт в общей ленте чата,
        // где кнопка под сообщением читается как оформление — строка прямым текстом говорит, что
        // от участника ждут. Текст общий с постом и DM о правке — см. EventMessageTemplate.
        private const val VOTE_CALL_TO_ACTION = EventMessageTemplate.VOTE_CALL_TO_ACTION
    }
}
