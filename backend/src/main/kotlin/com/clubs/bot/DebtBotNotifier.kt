package com.clubs.bot

import com.clubs.common.util.Money
import com.clubs.debt.DebtAmountChangedEvent
import com.clubs.debt.DebtClaimedEvent
import com.clubs.debt.DebtCreatedEvent
import com.clubs.debt.DebtPenalty
import com.clubs.debt.DebtPromisedEvent
import com.clubs.debt.DebtRejectedEvent
import com.clubs.debt.DebtReminder
import com.clubs.debt.DebtReminderKind
import com.clubs.debt.DebtReplacedEvent
import com.clubs.debt.DebtReplyEvent
import com.clubs.debt.DebtSettlement
import com.clubs.debt.DebtTotals
import com.clubs.debt.DebtWithContext
import com.clubs.debt.SettlementClaimedEvent
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.reputation.ReputationPolicy
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaRemainderService
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * DM по долгу (skladchina-v3 § 5, § 6): новый долг, «Отдал» с кнопками «Получил / Не получил»,
 * «Не получил», «Оплачу позже», ответ должника (заметка/чек), смена суммы, замена, сальдо пары,
 * напоминания шедулера и −40 за просрочку.
 * Слушатели работают после коммита; методы напоминаний зовёт [DebtScheduler].
 */
@Component
class DebtBotNotifier(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val gateway: ChatTelegramGateway,
    @Value("\${debts.overdue-weeks:3}") private val overdueWeeks: Long
) {
    private val log = LoggerFactory.getLogger(DebtBotNotifier::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'").withZone(ZoneId.of("Europe/Moscow"))
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.of("Europe/Moscow"))

    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtCreated(event: DebtCreatedEvent) {
        sendShareDm(event.debt, intro = "💰 Вас добавили в сбор «${event.debt.skladchinaTitle}» в клубе «${event.debt.clubName}».")
    }

    /** «Отдал»: получателю — с кнопками, ответ из DM равносилен нажатию в приложении. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtClaimed(event: DebtClaimedEvent) {
        val d = event.debt
        val text = "💸 ${d.debtor.firstName} говорит, что отдал ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}».\n\n" +
            "Подтвердите, когда деньги придут."
        sendDebtDecision(d, text)
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtRejected(event: DebtRejectedEvent) {
        val d = event.debt
        val telegramId = telegramIdOf(d.debt.debtorId) ?: return
        val text = if (event.dropped) {
            "❌ ${d.creditor.firstName} не подтвердил ваш перевод ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}», " +
                "а заказ уже сделан — вы выбыли, долга нет."
        } else buildString {
            append("❌ ${d.creditor.firstName} не нашёл ваш перевод ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}».")
            d.debt.rejectNote?.let { append("\nЗаметка: ").append(it) }
            append("\n\nПриложите чек в приложении, чтобы разобраться.")
        }
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/debts/with/${d.debt.creditorId}", OPEN_DEBTS_BUTTON)
    }

    /** «Оплачу позже»: получатель должен знать дату, а не гадать, почему тишина. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtPromised(event: DebtPromisedEvent) {
        val d = event.debt
        val telegramId = telegramIdOf(d.debt.creditorId) ?: return
        val text = "📅 ${d.debtor.firstName} обещает отдать ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}» " +
            "к ${d.debt.promisedAt?.format(dateFmt)}."
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON)
    }

    /** Ответ должника («Не согласен»: заметка и/или чек) — получателю, чтобы разбор не завис. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtReply(event: DebtReplyEvent) {
        val d = event.debt
        val telegramId = telegramIdOf(d.debt.creditorId) ?: return
        val text = buildString {
            append("💬 ${d.debtor.firstName} по долгу ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}»")
            d.debt.note?.takeIf { it.isNotBlank() }?.let { append(": «").append(it).append("»") }
            if (event.withReceipt) append("\nПриложил чек.")
        }
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON)
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtAmountChanged(event: DebtAmountChangedEvent) {
        val d = event.debt
        val telegramId = telegramIdOf(d.debt.debtorId) ?: return
        val text = "✏️ ${d.creditor.firstName} изменил вашу долю за «${d.skladchinaTitle}»: " +
            "${Money.rub(event.oldAmountKopecks)} → ${Money.rub(d.debt.amountKopecks)}."
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON)
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onDebtReplaced(event: DebtReplacedEvent) {
        val d = event.replacement
        telegramIdOf(event.replacedUserId)?.let {
            notificationService.sendDirectMessageWithDeepLink(
                it, "🔁 В сборе «${d.skladchinaTitle}» вас заменил ${d.debtor.firstName}: долг снят.",
                "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON
            )
        }
        sendShareDm(d, intro = "💰 Вас добавили в сбор «${d.skladchinaTitle}» в клубе «${d.clubName}» вместо другого участника.")
    }

    /** «Отдал Σ» по сальдо: получателю один DM с кнопками. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onSettlementClaimed(event: SettlementClaimedEvent) {
        val s = event.settlement
        sendSettlementDecision(
            s,
            "💸 ${event.payerName} говорит, что перевёл ${Money.rub(s.amountKopecks)} — сальдо по вашим долгам (${event.debtCount} шт.).\n\n" +
                "Подтвердите, когда деньги придут."
        )
    }

    /** Сальдо без ответа дольше 48 ч: получателю повтор с кнопками (раз в 3 дня, зовёт DebtScheduler). */
    fun sendSettlementReminder(s: DebtSettlement) {
        val payerName = userRepository.findById(s.payerId)?.firstName ?: "Участник"
        sendSettlementDecision(
            s,
            "⏳ $payerName говорит, что перевёл ${Money.rub(s.amountKopecks)} — сальдо по вашим долгам — ещё ${s.claimedAt.format(dateFmt)}. Ответьте:"
        )
    }

    private fun sendSettlementDecision(s: DebtSettlement, text: String) {
        val telegramId = telegramIdOf(s.payeeId) ?: return
        gateway.sendDmWithButtons(
            telegramId, text,
            listOf(
                listOf(
                    DmButton("✅ Получил", callbackData = SkladchinaCallbackService.SETTLE_CONFIRM_PREFIX + s.id),
                    DmButton("❌ Не получил", callbackData = SkladchinaCallbackService.SETTLE_REJECT_PREFIX + s.id)
                ),
                listOf(DmButton(OPEN_DEBTS_BUTTON, webAppPath = "/debts/with/${s.payerId}"))
            )
        )
        log.info("Settlement-decision DM sent: id={} payee={}", s.id, s.payeeId)
    }

    // --- Напоминания и штрафы (зовёт DebtScheduler) ---

    fun sendReminder(reminder: DebtReminder) {
        val d = reminder.debt
        val amount = Money.rub(d.debt.amountKopecks)
        when (reminder.kind) {
            DebtReminderKind.DUE_SOON -> sendToDebtor(d, "⏰ Завтра срок: $amount для ${d.creditor.firstName} за «${d.skladchinaTitle}».")
            DebtReminderKind.OVERDUE -> sendToDebtor(
                d, "⚠️ Долг $amount для ${d.creditor.firstName} за «${d.skladchinaTitle}», срок был ${d.debt.dueAt?.format(dateFmt)}. " +
                    "Если отдали — нажмите «Отдал»." + (if (d.skladchinaKind == SkladchinaKind.shared) "\n\n" + ReputationPolicy.skladchinaRulesLine() else "")
            )
            DebtReminderKind.PROMISE_DUE -> sendToDebtor(d, "📅 Вы обещали отдать $amount для ${d.creditor.firstName} за «${d.skladchinaTitle}» к сегодня.")
            DebtReminderKind.CLAIM_STALE -> sendDebtDecision(
                d, "⏳ ${d.debtor.firstName} говорит, что отдал $amount за «${d.skladchinaTitle}» ещё ${d.debt.claimedAt?.format(dateFmt)}. Ответьте:"
            )
            DebtReminderKind.MINUS_IN_WEEK -> sendToDebtor(
                d, "⚠️ Долг $amount для ${d.creditor.firstName} за «${d.skladchinaTitle}» просрочен. " +
                    "Через неделю, ${penaltyDate(d)}, репутация в клубе «${d.clubName}» снизится на 40. " +
                    "Отдайте или нажмите «Отдал», если уже перевели."
            )
            DebtReminderKind.MINUS_TOMORROW -> sendToDebtor(
                d, "⚠️ Завтра, ${penaltyDate(d)}, за долг $amount для ${d.creditor.firstName} за «${d.skladchinaTitle}» " +
                    "репутация в клубе «${d.clubName}» снизится на 40. Ещё можно успеть: отдайте и нажмите «Отдал»."
            )
        }
    }

    /** Момент списания −40: greatest(due_at, rejected_at) + overdue-weeks — та же формула, что в DebtReputationService. */
    private fun penaltyDate(d: DebtWithContext): String {
        val start = listOfNotNull(d.debt.dueAt, d.debt.rejectedAt).max()
        return start.plusWeeks(overdueWeeks).format(dateFmt)
    }

    /** Момент −40: должнику «снижена», получателю «простить или ждать?» с кнопкой «Простить» прямо в DM (PO 2026-09-13). */
    fun sendPenalty(penalty: DebtPenalty) {
        val d = penalty.debt
        val amount = Money.rub(d.debt.amountKopecks)
        if (penalty.reputationApplied) {
            sendToDebtor(
                d, "⚠️ Долг $amount за «${d.skladchinaTitle}» просрочен больше $overdueWeeks недель — " +
                    "репутация в клубе «${d.clubName}» снижена на 40."
            )
        }
        val creditorTelegramId = telegramIdOf(d.debt.creditorId) ?: return
        val text = "⚠️ ${d.debtor.firstName} так и не отдал $amount за «${d.skladchinaTitle}»" +
            (if (penalty.reputationApplied) " — репутация в клубе «${d.clubName}» снижена на 40." else ".") +
            "\n\nПростить долг или ждать дальше? Сбор останется открытым, пока долг не закрыт."
        gateway.sendDmWithButtons(
            creditorTelegramId, text,
            listOf(
                listOf(DmButton("🙏 Простить", callbackData = SkladchinaCallbackService.FORGIVE_PREFIX + d.debt.id)),
                listOf(DmButton(OPEN_SKLADCHINA_BUTTON, webAppPath = "/skladchina/${d.debt.skladchinaId}"))
            )
        )
    }

    /** «По желанию» со сроком: приглашённым, кто ещё не перевёл, за сутки до срока — один раз. */
    fun sendVoluntaryDeadlineReminder(s: Skladchina, userIds: Collection<UUID>) {
        val deadline = s.deadline ?: return
        val text = if (s.isFreeAmountRequired) {
            "⏰ Завтра, ${deadline.format(fmt)}, срок сбора «${s.title}». Все из списка должны, сумму выбираете сами: " +
                "переведите и нажмите «Перевёл» или обещайте сумму и дату. После срока остаток разделится поровну между теми, кто промолчал."
        } else {
            "⏰ Завтра, ${deadline.format(fmt)}, закрывается сбор «${s.title}» по желанию. " +
                "Если хотите скинуться — переведите и нажмите «Перевёл»."
        }
        userRepository.findTelegramIds(userIds).forEach {
            notificationService.sendDirectMessageWithDeepLink(it, text, "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON)
        }
    }

    /** § 3.5, по сроку: молчунам — их долг из остатка, создателю — кому что назначено. */
    fun sendSilentSplit(s: Skladchina, split: SkladchinaRemainderService.Outcome.Split) {
        val names = userRepository.findByIds(split.debts.map { it.debtorId }).associate { it.id!! to it.firstName }
        val k = split.debts.size
        split.debts.forEach { d ->
            val telegramId = telegramIdOf(d.debtorId) ?: return@forEach
            val text = "💸 «${s.title}»: срок вышел, вы не ответили. С вас ${Money.rub(d.amountKopecks)}" +
                (d.dueAt?.let { " до ${it.format(fmt)}" } ?: "") +
                " — остаток ${Money.rub(split.remainderKopecks)} поровну на $k. После перевода нажмите «Отдал»."
            notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON)
        }
        val creatorTelegramId = telegramIdOf(s.creatorId) ?: return
        val list = split.debts.joinToString(", ") { "${names[it.debtorId] ?: "участник"} ${Money.rub(it.amountKopecks)}" }
        notificationService.sendDirectMessageWithDeepLink(
            creatorTelegramId, "💸 «${s.title}»: срок вышел. Остаток ${Money.rub(split.remainderKopecks)} ушёл в долг: $list.",
            "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON
        )
    }

    /** § 3.5, по сроку: все ответили, но счёт не закрыт — решает создатель. */
    fun sendShortfall(s: Skladchina, remainderKopecks: Long) {
        val telegramId = telegramIdOf(s.creatorId) ?: return
        val text = "💸 «${s.title}»: срок вышел, все ответили, не хватило ${Money.rub(remainderKopecks)}. " +
            "Закрыть сбор или добавить долг — решать вам."
        gateway.sendDmWithButtons(
            telegramId, text,
            listOf(
                listOf(DmButton("✅ Закрыть сбор", callbackData = SkladchinaCallbackService.CLOSE_PREFIX + s.id)),
                listOf(DmButton(OPEN_SKLADCHINA_BUTTON, webAppPath = "/skladchina/${s.id}"))
            )
        )
    }

    /** «По желанию» со сроком: создателю в день срока — «закрыть сбор?» с кнопкой прямо в DM. */
    fun sendCloseReminder(s: Skladchina, totals: DebtTotals) {
        val telegramId = telegramIdOf(s.creatorId) ?: return
        val text = "⏰ Срок сбора «${s.title}» прошёл: получено ${Money.rub(totals.receivedKopecks)}" +
            (if (totals.claimedCount > 0) ", ждут подтверждения: ${totals.claimedCount}" else "") + ". Закрыть сбор?"
        gateway.sendDmWithButtons(
            telegramId, text,
            listOf(
                listOf(DmButton("✅ Закрыть сбор", callbackData = SkladchinaCallbackService.CLOSE_PREFIX + s.id)),
                listOf(DmButton(OPEN_SKLADCHINA_BUTTON, webAppPath = "/skladchina/${s.id}"))
            )
        )
    }

    /** per_head: создателю в срок и раз в день — заказ это действие в жизни, автозаказа нет. */
    fun sendOrderReminder(s: Skladchina, totals: DebtTotals) {
        val telegramId = telegramIdOf(s.creatorId) ?: return
        val text = "🛒 «${s.title}»: срок вышел, пора заказывать. Оплатили ${totals.receivedCount}, ждём ${totals.openCount}."
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON)
    }

    // --- helpers ---

    private fun sendShareDm(d: DebtWithContext, intro: String) {
        val telegramId = telegramIdOf(d.debt.debtorId) ?: return
        val text = buildString {
            append(intro)
            append("\n\n💵 Ваша доля: ").append(Money.rub(d.debt.amountKopecks))
            d.debt.dueAt?.let { append("\n⏳ До: ").append(it.format(fmt)) }
            append("\n\n💳 Реквизиты:\n").append(d.paymentLink)
            d.paymentMethodNote?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            append("\n\nПосле перевода нажмите «Отдал» — ${d.creditor.firstName} подтвердит.")
            if (d.skladchinaKind == SkladchinaKind.shared) append("\n\n").append(ReputationPolicy.skladchinaRulesLine())
        }
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON)
    }

    private fun sendDebtDecision(d: DebtWithContext, text: String) {
        val telegramId = telegramIdOf(d.debt.creditorId) ?: return
        gateway.sendDmWithButtons(
            telegramId, text,
            listOf(
                listOf(
                    DmButton("✅ Получил", callbackData = SkladchinaCallbackService.CONFIRM_PREFIX + d.debt.id),
                    DmButton("❌ Не получил", callbackData = SkladchinaCallbackService.REJECT_PREFIX + d.debt.id)
                ),
                listOf(DmButton(OPEN_SKLADCHINA_BUTTON, webAppPath = "/skladchina/${d.debt.skladchinaId}"))
            )
        )
        log.info("Debt-decision DM sent: debtId={} creditor={}", d.debt.id, d.debt.creditorId)
    }

    private fun sendToDebtor(d: DebtWithContext, text: String) {
        val telegramId = telegramIdOf(d.debt.debtorId) ?: return
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${d.debt.skladchinaId}", OPEN_SKLADCHINA_BUTTON)
    }

    private fun telegramIdOf(userId: UUID): Long? {
        val telegramId = userRepository.findById(userId)?.telegramId
        if (telegramId == null) log.warn("Debt DM SKIPPED — telegramId missing: userId={}", userId)
        return telegramId
    }

    companion object {
        const val OPEN_SKLADCHINA_BUTTON = "💰 Открыть сбор"
        const val OPEN_DEBTS_BUTTON = "📒 Открыть долги"
    }
}
