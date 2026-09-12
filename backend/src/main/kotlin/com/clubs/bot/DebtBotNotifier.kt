package com.clubs.bot

import com.clubs.common.util.Money
import com.clubs.debt.DebtAmountChangedEvent
import com.clubs.debt.DebtClaimedEvent
import com.clubs.debt.DebtCreatedEvent
import com.clubs.debt.DebtPenalty
import com.clubs.debt.DebtRejectedEvent
import com.clubs.debt.DebtReminder
import com.clubs.debt.DebtReminderKind
import com.clubs.debt.DebtReplacedEvent
import com.clubs.debt.DebtTotals
import com.clubs.debt.DebtWithContext
import com.clubs.debt.SettlementClaimedEvent
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.reputation.ReputationPolicy
import com.clubs.skladchina.Skladchina
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
 * «Не получил», смена суммы, замена, сальдо пары, напоминания шедулера и −40 за просрочку.
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
        val telegramId = telegramIdOf(s.payeeId) ?: return
        val text = "💸 ${event.payerName} говорит, что перевёл ${Money.rub(s.amountKopecks)} — сальдо по вашим долгам (${event.debtCount} шт.).\n\n" +
            "Подтвердите, когда деньги придут."
        gateway.sendDmWithButtons(
            telegramId, text,
            listOf(
                listOf(
                    DmButton("✅ Получил", callbackData = DebtCallbackService.SETTLE_CONFIRM_PREFIX + s.id),
                    DmButton("❌ Не получил", callbackData = DebtCallbackService.SETTLE_REJECT_PREFIX + s.id)
                ),
                listOf(DmButton(OPEN_DEBTS_BUTTON, webAppPath = "/debts/with/${s.payerId}"))
            )
        )
        log.info("Settlement-claimed DM sent: id={} payee={}", s.id, s.payeeId)
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
        }
    }

    fun sendPenalty(penalty: DebtPenalty) {
        val d = penalty.debt
        sendToDebtor(
            d, "⚠️ Долг ${Money.rub(d.debt.amountKopecks)} за «${d.skladchinaTitle}» просрочен больше $overdueWeeks недель — " +
                "репутация в клубе «${d.clubName}» снижена на 40."
        )
    }

    /** per_head: создателю в срок и раз в день — заказ это действие в жизни, автозаказа нет. */
    fun sendOrderReminder(s: Skladchina, totals: DebtTotals) {
        val telegramId = telegramIdOf(s.creatorId) ?: return
        val text = "🛒 «${s.title}»: срок вышел, пора заказывать. Оплатили ${totals.receivedCount}, ждём ${totals.openCount}."
        notificationService.sendDirectMessageWithDeepLink(telegramId, text, "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON)
    }

    /** Напоминание о сроке сбора тем должникам, кого чат-упоминание не покрыло. */
    fun sendDeadlineFallback(s: Skladchina, userIds: Collection<UUID>) {
        val deadline = s.deadline ?: return
        val text = "⏰ Напоминание: сбор «${s.title}» — срок ${deadline.format(fmt)}."
        userRepository.findTelegramIds(userIds).forEach {
            notificationService.sendDirectMessageWithDeepLink(it, text, "/skladchina/${s.id}", OPEN_SKLADCHINA_BUTTON)
        }
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
                    DmButton("✅ Получил", callbackData = DebtCallbackService.CONFIRM_PREFIX + d.debt.id),
                    DmButton("❌ Не получил", callbackData = DebtCallbackService.REJECT_PREFIX + d.debt.id)
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
