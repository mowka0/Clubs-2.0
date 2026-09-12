package com.clubs.chatlink

import com.clubs.common.util.Money
import com.clubs.debt.DebtTotals
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
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

/** Всё, что нужно тексту поста: сбор, итоги по долгам, кто в деле / кто ещё не оплатил, имя создателя. */
data class ChatStatusView(
    val skladchina: Skladchina,
    val totals: DebtTotals,
    val enrolledCount: Int,
    val creatorName: String,
    /** Ещё не оплатили (waiting/promised) — «Ждём:» до срока. */
    val waiting: List<ChatMention>,
    /** Отметились «В деле» на этапе записи. */
    val enrolled: List<ChatMention>,
    val now: OffsetDateTime
)

/**
 * Тексты «живого статуса сбора» (docs/modules/skladchina-v3.md § 6): пост по виду и стадии,
 * финал при закрытии, напоминание о сроке. HTML parse_mode ради `text_mention`-упоминаний
 * (`tg://user?id=…`), поэтому ВЕСЬ пользовательский ввод (заголовок, имена) экранируется.
 * Только форматирование — данные приносит [SkladchinaChatStatusService].
 */
@Component
class SkladchinaChatStatusRenderer(
    @Value("\${telegram.bot-username}") private val botUsername: String
) {
    // Читатель в чате не имеет часового пояса устройства — рендерим МСК с явной пометкой.
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'").withZone(ZoneId.of("Europe/Moscow"))

    /** Url-кнопка для ГРУППЫ — deep link Main Mini App (WebApp-кнопки в группах запрещены). */
    fun skladchinaUrl(skladchinaId: UUID): String =
        "https://t.me/$botUsername?startapp=skladchina_$skladchinaId"

    /** Кнопка под постом ведёт в приложение; текст зовёт к действию стадии. */
    fun buttonText(s: Skladchina): String = when {
        s.isEnrolling -> "В деле"
        s.kind == SkladchinaKind.per_head && s.orderedAt == null -> "Беру"
        else -> "Открыть сбор"
    }

    fun statusText(view: ChatStatusView): String {
        val s = view.skladchina
        return when {
            s.isEnrolling -> enrollingText(view)
            s.kind == SkladchinaKind.per_head -> perHeadText(view)
            s.kind == SkladchinaKind.voluntary -> voluntaryText(view)
            else -> sharedText(view)
        }
    }

    /**
     * Финал при закрытии: нейтральный, БЕЗ списка неоплативших и без упоминаний (северная звезда —
     * не позорим публично, полные детали живут в приложении и DM).
     */
    fun closedText(view: ChatStatusView): String {
        val s = view.skladchina
        val t = view.totals
        val header = "${emoji(s)} ${escapeHtml(s.title)}\n"
        return when {
            s.status == SkladchinaStatus.cancelled && s.enrollmentUntil != null && t.debtCount == 0 ->
                header + "Не набрали: в деле ${view.enrolledCount}" +
                    (s.minParticipants?.let { " из $it" } ?: "") + ". Сбор отменён, денег никто не переводил"
            s.status == SkladchinaStatus.cancelled -> header + "Сбор отменён"
            s.kind == SkladchinaKind.per_head ->
                header + "✅ Куплено ${t.receivedCount} · ${Money.rub(t.receivedKopecks)} · приём закрыт"
            else -> header + "✅ Собрано ${Money.rub(t.receivedKopecks)} · оплатили ${t.receivedCount} из ${t.debtCount}"
        }
    }

    /**
     * Напоминание о сроке (за 24 часа). Публичный текст БЕЗ цены просрочки — не коллектор; полный
     * текст остаётся в DM. [pending] уже обрезан вызывающим до [MAX_MENTIONS] — упомянутый = пингнутый.
     */
    fun reminderText(title: String, deadline: OffsetDateTime, pending: List<ChatMention>): String {
        val sb = StringBuilder()
        sb.append("⏰ Напоминание: сбор «").append(escapeHtml(title)).append("» — срок ")
            .append(deadline.format(fmt)).append(".")
        if (pending.isNotEmpty()) {
            sb.append("\nЕщё не оплатили: ").append(pending.joinToString(", ") { mention(it) })
        }
        return sb.toString()
    }

    private fun sharedText(view: ChatStatusView): String {
        val s = view.skladchina
        val t = view.totals
        val target = t.targetKopecks ?: s.amountKopecks ?: 0L
        val sb = StringBuilder()
        sb.append("💰 ").append(escapeHtml(s.title)).append("\n")
        if (t.debtCount > 0 && target > 0) sb.append("по ").append(Money.rub(target / t.debtCount)).append(" с человека · ")
        sb.append("собирает ").append(escapeHtml(view.creatorName)).append("\n")
        sb.append(progressBar(t, target)).append("\n")
        sb.append("💵 Получено ").append(Money.rub(t.receivedKopecks)).append(" из ").append(Money.rub(target))
            .append(" · оплатили ").append(t.receivedCount).append(" из ").append(t.debtCount).append("\n")
        appendDeadline(sb, s, view.now, t)
        // До срока — имена; после срока только число (PO 2026-09-12): имена видят получатель и должник.
        if (!isPastDeadline(s, view.now)) mentionsLine(view.waiting)?.let { sb.append("\n\nЖдём: ").append(it) }
        return sb.toString()
    }

    private fun enrollingText(view: ChatStatusView): String {
        val s = view.skladchina
        val sb = StringBuilder()
        sb.append("🎾 ").append(escapeHtml(s.title)).append("\n")
        sb.append(Money.rub(s.amountKopecks ?: 0L)).append(" на группу, поровну между теми, кто в деле\n")
        sb.append("👥 В деле ").append(view.enrolledCount)
        s.minParticipants?.let { sb.append(" · нужно ").append(it) }
        sb.append("\n⏳ Отметиться до ").append(s.enrollmentUntil!!.format(fmt))
        mentionsLine(view.enrolled)?.let { sb.append("\n\nВ деле: ").append(it) }
        return sb.toString()
    }

    private fun perHeadText(view: ChatStatusView): String {
        val s = view.skladchina
        val t = view.totals
        val sb = StringBuilder()
        sb.append("🎫 ").append(escapeHtml(s.title)).append("\n")
        if (s.orderedAt != null) {
            sb.append("✅ Куплено ").append(t.receivedCount).append(" · ").append(Money.rub(t.receivedKopecks)).append(" · приём закрыт")
            return sb.toString()
        }
        sb.append(Money.rub(s.amountKopecks ?: 0L)).append(" за штуку · ").append(escapeHtml(view.creatorName))
            .append(" покупает ").append(s.deadline!!.format(fmt)).append(" на тех, кто оплатил\n")
        sb.append("👥 Берут ").append(t.debtCount).append(" · оплатили ").append(t.receivedCount).append("\n")
        appendDeadline(sb, s, view.now, t)
        return sb.toString()
    }

    private fun voluntaryText(view: ChatStatusView): String {
        val s = view.skladchina
        val t = view.totals
        val sb = StringBuilder()
        sb.append("🎁 ").append(escapeHtml(s.title)).append("\n")
        sb.append("по желанию")
        s.amountKopecks?.let { sb.append(" · ориентир ").append(Money.rub(it)) }
        s.deadline?.let { sb.append(" · до ").append(it.format(fmt)) }
        sb.append(" · собирает ").append(escapeHtml(view.creatorName)).append("\n")
        sb.append("💵 Получено ").append(Money.rub(t.receivedKopecks))
        return sb.toString()
    }

    private fun appendDeadline(sb: StringBuilder, s: Skladchina, now: OffsetDateTime, t: DebtTotals) {
        val deadline = s.deadline ?: return
        if (isPastDeadline(s, now)) {
            sb.append("⏳ Срок вышел · не оплатили ").append(t.openCount)
        } else {
            sb.append("⏳ До ").append(deadline.format(fmt))
        }
    }

    private fun isPastDeadline(s: Skladchina, now: OffsetDateTime): Boolean =
        s.deadline != null && !now.isBefore(s.deadline)

    /** Полоса из десяти ячеек по деньгам: 🟩 получено, 🟨 говорят, что отдали, ⬜ ждём. */
    private fun progressBar(t: DebtTotals, target: Long): String {
        if (target <= 0) return "⬜".repeat(BAR_CELLS)
        val green = (t.receivedKopecks * BAR_CELLS / target).toInt().coerceIn(0, BAR_CELLS)
        val yellow = ((t.receivedKopecks + t.claimedKopecks) * BAR_CELLS / target).toInt().coerceIn(green, BAR_CELLS) - green
        return "🟩".repeat(green) + "🟨".repeat(yellow) + "⬜".repeat(BAR_CELLS - green - yellow)
    }

    private fun emoji(s: Skladchina): String = when (s.kind) {
        SkladchinaKind.shared -> "💰"
        SkladchinaKind.per_head -> "🎫"
        SkladchinaKind.voluntary -> "🎁"
    }

    /** «@a, @b … и ещё k» — стена из десятков меншенов хуже недопинга, режем по [MAX_MENTIONS]. */
    private fun mentionsLine(people: List<ChatMention>): String? {
        if (people.isEmpty()) return null
        val shown = people.take(MAX_MENTIONS).joinToString(", ") { mention(it) }
        val rest = people.size - MAX_MENTIONS
        return if (rest > 0) "$shown и ещё $rest" else shown
    }

    private fun mention(m: ChatMention): String =
        "<a href=\"tg://user?id=${m.telegramId}\">${escapeHtml(m.firstName.take(MAX_MENTION_NAME_LENGTH))}</a>"

    /** HTML parse_mode: `&`, `<`, `>` в пользовательском вводе ломали бы разметку/давали инъекцию тегов. */
    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        // Максимум text_mention-упоминаний в одном сообщении — дальше «и ещё k» (без пинга)
        const val MAX_MENTIONS = 15

        // Обрезка имени в меншене: first_name в Telegram может быть до 255 символов — 15 таких
        // имён с экранированием пробили бы лимит 4096 символов на сообщение.
        const val MAX_MENTION_NAME_LENGTH = 64

        // Ячеек в полосе прогресса.
        private const val BAR_CELLS = 10
    }
}
