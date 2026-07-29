package com.clubs.event

import java.time.format.DateTimeFormatter

/**
 * Единый шаблон сообщений о встрече для личных DM и постов/закрепа в чате клуба
 * (решение PO 2026-07-26: «указывай формат события»). До него подача расползлась —
 * DM, живой закреп и уведомления о правках рендерили дату и место каждый по-своему,
 * и формат встречи упоминался только эмодзи в заголовке DM о создании.
 *
 * Форма:
 * ```
 * <b>Срочная встреча</b>
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
    fun formatName(event: Event): String = when {
        event.isUrgent -> "Срочная встреча"
        event.isOpenEvent -> "Открытая встреча"
        else -> "Обычная встреча"
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
        sb.append(event.participantLimit?.let { "👥 Мест — $it" } ?: OPEN_EVENT_LIMIT_LINE)
        return sb.toString()
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

    /** HTML parse_mode: `&`, `<`, `>` в пользовательском вводе ломали бы разметку/давали инъекцию тегов. */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Открытая встреча вместо числа мест сообщает свою суть: приходят все желающие.
    private const val OPEN_EVENT_LIMIT_LINE = "👥 Без лимита мест — приходят все желающие"
}
