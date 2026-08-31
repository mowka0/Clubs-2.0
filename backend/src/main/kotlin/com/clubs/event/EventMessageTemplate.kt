package com.clubs.event

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Единый шаблон сообщений о встрече для личных DM и постов/закрепа в чате клуба
 * (решение PO 2026-07-26: «указывай формат события»). До него подача расползлась —
 * DM, живой закреп и уведомления о правках рендерили дату и место каждый по-своему,
 * и формат встречи упоминался только эмодзи в заголовке DM о создании.
 *
 * Форма:
 * ```
 * <b>Встреча: не больше 20</b>
 *
 * Название
 * Описание
 * когда: 27.07.2026 09:15 МСК
 * где: дом
 *
 * ✅ Подтвердили — 0 из 20
 * ⏳ Подтвердить до — 27.07.2026 09:15 МСК
 * ```
 *
 * Требует **HTML parse_mode** у отправителя (жирный заголовок), поэтому весь пользовательский
 * ввод — название, описание, место — проходит через [escapeHtml]: неэкранированный `&` или `<`
 * ломает разметку всего сообщения, и оно молча не доставляется.
 */
object EventMessageTemplate {

    /** Формат встречи словами — он же заголовок сообщения. Словарь общий с бейджами карточек. */
    fun formatName(event: Event): String = when (event.format) {
        EventFormat.MIN -> "Встреча: не меньше ${event.participantLimit}"
        EventFormat.MAX -> "Встреча: не больше ${event.participantLimit}"
        EventFormat.ANY -> "Встреча: сколько придёт"
    }

    /**
     * Шапка: жирный формат, затем что / о чём / когда / где. Пустые поля (описание, место)
     * не рендерятся вовсе — строка-заготовка «где: —» выглядит как недоделка, а не как факт.
     */
    fun head(event: Event, fmt: DateTimeFormatter): String {
        val sb = StringBuilder("<b>${escapeHtml(formatName(event))}</b>\n\n")
        sb.append(escapeHtml(event.title)).append("\n")
        event.description?.takeIf { it.isNotBlank() }?.let { sb.append(escapeHtml(it)).append("\n") }
        sb.append("когда: ").append(event.eventDatetime.format(fmt)).append("\n")
        event.locationDisplay?.let { sb.append("где: ").append(escapeHtml(it)) }
        return sb.toString().trimEnd()
    }

    /**
     * Счётчики Этапа 1 (идёт голосование). У открытой встречи мест не бывает — вместо числа
     * сообщаем сам факт формата, иначе «Мест — null» или тишина читались бы как ошибка.
     */
    fun stage1Stats(event: Event, going: Int, maybe: Int): String {
        val sb = StringBuilder("✅ Идут — $going\n🤔 Возможно — $maybe\n")
        sb.append(seatsLine(event))
        return sb.toString()
    }

    /**
     * Хвост ЛИЧНОГО сообщения: только неизменный факт — что означает число участников. Живых
     * счётчиков тут намеренно нет (PO 2026-08-08): DM отправляется один раз и не перерисовывается,
     * поэтому «✅ Идут — 0» навсегда оставался нулём и спорил с закрепом в чате, который как раз
     * обновляется по ходу голосования.
     */
    fun dmFacts(event: Event): String = seatsLine(event)

    /**
     * Счётчики НАБОРА СОСТАВА (формат 🎟, V83): голос «Иду» уже кладёт в состав, поэтому в закрепе
     * стоит не «идут», а «собрались N из M» — и прямой ответ на вопрос «сколько ещё нужно».
     * Строка дедлайна объясняет, что будет, если не наберём: без неё набор читается как
     * бессрочный, и голосовать «потом» кажется безопасным.
     */
    fun rosterStats(event: Event, confirmed: Int, deadline: OffsetDateTime, fmt: DateTimeFormatter): String {
        val limit = event.participantLimit ?: return ""
        val left = (limit - confirmed).coerceAtLeast(0)
        val counts = when (event.format) {
            // Порог мог быть уже взят и даже перерасти себя — «нужно ещё 0» читалось бы как сбой.
            EventFormat.MIN -> if (left > 0) "👥 Собрались $confirmed из $limit — нужно ещё $left."
                else "👥 Собрались $confirmed — минимум набран."
            EventFormat.MAX -> if (left > 0) "👥 Заняты $confirmed из $limit мест — свободно $left."
                else "👥 Мест нет: $confirmed из $limit. Дальше — очередь на замену."
            EventFormat.ANY -> return ""
        }
        // Что случится в дедлайн — свойство формата, и молчать об этом нельзя: без этой строки
        // набор читается как бессрочный, и голосовать «потом» кажется безопасным.
        val outcome = if (event.format == EventFormat.MIN) " Не наберём — встреча отменится."
            else " Состав закроется тем, кто успел."
        return "$counts\n⏳ Набор закрывается ${deadline.format(fmt)}.$outcome"
    }

    /** Состав закрыт: встреча состоится. Очередь упоминаем, только если она есть. */
    fun rosterClosedStats(event: Event, confirmed: Int, waitlisted: Int): String {
        // У MIN состав мог перерасти порог, и знаменатель «7 из 6» читался бы как опечатка.
        val head = when (event.format) {
            EventFormat.MIN -> "✅ Состав собран: $confirmed (нужно было ${event.participantLimit})."
            EventFormat.MAX -> "✅ Состав собран: $confirmed из ${event.participantLimit}."
            EventFormat.ANY -> "✅ Состав собран: $confirmed."
        }
        val queue = if (waitlisted > 0) {
            "\n📋 В очереди — $waitlisted: если кто-то не сможет, место перейдёт им."
        } else ""
        return head + queue
    }

    /**
     * Счётчики Этапа 2 (подтверждение мест). Дедлайн подтверждения = старт встречи.
     * У открытой встречи гонки за места нет — счёт без знаменателя и без строки очереди.
     */
    fun stage2Stats(event: Event, confirmed: Int, waitlisted: Int, fmt: DateTimeFormatter): String {
        val sb = StringBuilder()
        if (event.participantLimit != null) {
            sb.append("✅ Подтвердили — $confirmed из ${event.participantLimit}\n")
            if (waitlisted > 0) sb.append("📋 В очереди — $waitlisted\n")
        } else {
            sb.append("✅ Подтвердили — $confirmed\n")
        }
        sb.append("⏳ Подтвердить до — ${event.eventDatetime.format(fmt)}")
        return sb.toString()
    }

    /**
     * Что означает число участников — одна строка на все бот-поверхности (DM, /status, закреп).
     * Формат без лимита сообщает свою суть, а не «Мест — null».
     */
    fun seatsLine(event: Event): String = when (event.format) {
        EventFormat.MIN -> "👥 Нужно минимум ${event.participantLimit} — иначе встреча отменится"
        EventFormat.MAX -> "👥 Мест — ${event.participantLimit}"
        EventFormat.ANY -> "👥 Без ограничений — приходят все желающие, репутация не считается"
    }

    /** HTML parse_mode: `&`, `<`, `>` в пользовательском вводе ломали бы разметку/давали инъекцию тегов. */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Призыв проголосовать. Живёт здесь, а не в отдельных рендерах: одну и ту же строку
     * показывают живой закреп чата, пост о правке встречи и DM о правке — расхождение
     * формулировок между ними читалось бы как разные действия.
     */
    const val VOTE_CALL_TO_ACTION = "Проголосуй в клубе!"
}
