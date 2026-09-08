package com.clubs.chatlink

import com.clubs.generated.jooq.enums.SkladchinaStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Участник для text_mention-упоминания в чате: работает без username, по telegram_id. */
data class ChatMention(
    val telegramId: Long,
    val firstName: String
)

/**
 * Тексты «живого статуса сбора» (слайс 3.5): статус прогресса складчины, финал при закрытии
 * и напоминание о дедлайне. В отличие от [LivePinRenderer] — HTML parse_mode: он нужен для
 * `text_mention`-упоминаний (`tg://user?id=…`), поэтому ВЕСЬ пользовательский ввод
 * (заголовок, имена) экранируется. Только форматирование — данные приносит
 * [SkladchinaChatStatusService].
 */
@Component
class SkladchinaChatStatusRenderer(
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    // Как в LivePinRenderer: читатель в чате не имеет часового пояса устройства —
    // рендерим МСК с явной пометкой.
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
        .withZone(ZoneId.of("Europe/Moscow"))

    /**
     * Url-кнопка для ГРУППЫ — deep link Main Mini App (WebApp-кнопки в группах запрещены).
     * DeepLinkHandler фронта парсит `skladchina_<uuid>` из startParam.
     */
    fun skladchinaUrl(skladchinaId: UUID): String =
        "https://t.me/$botUsername?startapp=skladchina_$skladchinaId"

    fun buttonText(): String = "Открыть сбор"

    /**
     * Живой статус: полоса прогресса, люди, деньги, дедлайн и «Ждём:» с упоминаниями. Пингует
     * только ПЕРВЫЙ пост — последующие редактирования по механике Telegram уведомлений не создают.
     *
     * Общая сумма в чате показывается с 2026-09-08 (просьба PO: в посте было слишком мало
     * информации, люди уходили в приложение за цифрой, которая и так объявлена в сборе). Кто
     * сколько внёс — по-прежнему не светим: это остаётся видом организатора.
     */
    fun statusText(
        title: String,
        paidCount: Int,
        confirmedCount: Int,
        participantCount: Int,
        collectedKopecks: Long,
        totalGoalKopecks: Long?,
        deadline: OffsetDateTime,
        // Срок прошёл, но сбор ещё открыт: организатор его сводит (редакция PO 2026-09-09).
        deadlinePassed: Boolean,
        pending: List<ChatMention>
    ): String {
        val sb = StringBuilder()
        sb.append("💰 ").append(escapeHtml(title)).append("\n")
        sb.append(progressBar(confirmedCount, paidCount, participantCount)).append("\n")
        sb.append("👥 Скинулись — ").append(paidCount).append(" из ").append(participantCount)
        // Сверенные показываем, только когда организатор уже что-то сверил и что-то ещё нет —
        // иначе строка повторяет предыдущую и шумит.
        if (confirmedCount in 1 until paidCount) sb.append(" · сверено ").append(confirmedCount)
        sb.append("\n")
        sb.append("💵 Собрано ").append(formatRubles(collectedKopecks))
        totalGoalKopecks?.let { sb.append(" из ").append(formatRubles(it)) }
        sb.append(" ₽\n")
        // После срока «⏳ До 8 сентября» врало бы: сбор живёт, пока организатор его не сведёт.
        if (deadlinePassed) {
            sb.append("⏳ Срок вышел — организатор сводит сбор")
        } else {
            sb.append("⏳ До ").append(deadline.format(fmt))
        }
        mentionsLine(pending)?.let { sb.append("\n\n").append("Ждём: ").append(it) }
        return sb.toString()
    }

    /**
     * Полоса прогресса в чате — единственная доступная здесь графика: 🟩 деньги, которые
     * организатор сверил, 🟨 заявленные и ещё не сверенные, ⬜ неоплаченные доли. Ровно
     * [PROGRESS_CELLS] ячеек, чтобы полоса не «дышала» между перерисовками.
     */
    private fun progressBar(confirmedCount: Int, paidCount: Int, participantCount: Int): String {
        if (participantCount <= 0) return "⬜".repeat(PROGRESS_CELLS)
        val paidCells = (paidCount * PROGRESS_CELLS + participantCount - 1) / participantCount
        val confirmedCells = confirmedCount * PROGRESS_CELLS / participantCount
        // Заявленная оплата всегда видна хотя бы одной ячейкой: увидев пустую полосу после своего
        // «Я оплатил», человек решит, что отметка не прошла.
        val filled = paidCells.coerceAtMost(PROGRESS_CELLS)
        val green = confirmedCells.coerceAtMost(filled)
        return "🟩".repeat(green) + "🟨".repeat(filled - green) + "⬜".repeat(PROGRESS_CELLS - filled)
    }

    /** Рубли из копеек с разделителем тысяч: «4 000». */
    private fun formatRubles(kopecks: Long): String =
        (kopecks / 100).toString().reversed().chunked(3).joinToString("\u00A0").reversed()

    /**
     * Финал при закрытии: нейтральный, БЕЗ списка неоплативших и без упоминаний
     * (северная звезда — не позорим публично, полные детали живут в приложении и DM).
     */
    fun closedText(title: String, finalStatus: SkladchinaStatus, paidCount: Int, participantCount: Int): String {
        val header = "💰 ${escapeHtml(title)}\n"
        return when (finalStatus) {
            SkladchinaStatus.cancelled -> header + "Сбор отменён"
            SkladchinaStatus.closed_success -> header + "Сбор закрыт · скинулись $paidCount из $participantCount ✅"
            else -> header + "Сбор закрыт · скинулись $paidCount из $participantCount"
        }
    }

    /**
     * Напоминание о дедлайне (пинг №2, только «важные сборы»). Публичный текст БЕЗ цены
     * молчания (−40) — не коллектор; полный текст остаётся в DM-фоллбеке для тех, кого
     * нет в чате. [pending] уже обрезан вызывающим до [MAX_MENTIONS] — упомянутый = пингнутый.
     */
    fun reminderText(title: String, deadline: OffsetDateTime, pending: List<ChatMention>): String {
        val sb = StringBuilder()
        sb.append("⏰ Напоминание: сбор «").append(escapeHtml(title)).append("» закрывается ")
            .append(deadline.format(fmt)).append(".")
        if (pending.isNotEmpty()) {
            sb.append("\nЕщё не ответили: ").append(pending.joinToString(", ") { mention(it) })
        }
        return sb.toString()
    }

    /** «Ждём: @a, @b … и ещё k» — стена из десятков меншенов хуже недопинга, режем по [MAX_MENTIONS]. */
    private fun mentionsLine(pending: List<ChatMention>): String? {
        if (pending.isEmpty()) return null
        val shown = pending.take(MAX_MENTIONS).joinToString(", ") { mention(it) }
        val rest = pending.size - MAX_MENTIONS
        return if (rest > 0) "$shown и ещё $rest" else shown
    }

    private fun mention(m: ChatMention): String =
        "<a href=\"tg://user?id=${m.telegramId}\">${escapeHtml(m.firstName.take(MAX_MENTION_NAME_LENGTH))}</a>"

    /** HTML parse_mode: `&`, `<`, `>` в пользовательском вводе ломали бы разметку/давали инъекцию тегов. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        // Ячеек в полосе прогресса живого статуса: 10 — читается на узком экране телефона
        // и даёт шаг 10%, которого хватает, чтобы движение сбора было заметно.
        const val PROGRESS_CELLS = 10

        // Максимум text_mention-упоминаний в одном сообщении — дальше «и ещё k» (без пинга)
        const val MAX_MENTIONS = 15

        // Обрезка имени в меншене: first_name в Telegram может быть до 255 символов — 15 таких
        // имён с экранированием пробили бы лимит 4096 символов на сообщение, и пост молча бы
        // не создался (находка security-ревью).
        const val MAX_MENTION_NAME_LENGTH = 64
    }
}
