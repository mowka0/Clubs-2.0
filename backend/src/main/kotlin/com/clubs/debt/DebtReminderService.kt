package com.clubs.debt

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

enum class DebtReminderKind {
    /** Должнику: завтра срок. */
    DUE_SOON,
    /** Должнику: срок прошёл (в день срока, затем раз в неделю). */
    OVERDUE,
    /** Должнику: сегодня дата, к которой обещал. */
    PROMISE_DUE,
    /** Получателю: «Отдал» без ответа дольше 48 ч (раз в 3 дня). */
    CLAIM_STALE,
    /** Должнику (shared): через неделю за просрочку спишут −40. */
    MINUS_IN_WEEK,
    /** Должнику (shared): завтра за просрочку спишут −40. */
    MINUS_TOMORROW
}

data class DebtReminder(val kind: DebtReminderKind, val debt: DebtWithContext)

/**
 * Что кому напомнить (§ 5). Штампы ставятся ДО отправки, в одной транзакции с выборкой — повторный
 * тик никогда не отправит дважды, а упавший DM теряется best-effort, как и везде.
 */
@Service
class DebtReminderService(
    private val debtRepository: DebtRepository,
    @Value("\${skladchinas.deadline-reminder-minutes-before:1440}") private val dueSoonMinutes: Long,
    @Value("\${debts.claim-stale-hours:48}") private val claimStaleHours: Long,
    @Value("\${debts.overdue-weeks:3}") private val overdueWeeks: Long
) {

    @Transactional
    fun collect(now: OffsetDateTime): List<DebtReminder> {
        val reminders = mutableListOf<DebtReminder>()
        debtRepository.findDueSoon(now, now.plusMinutes(dueSoonMinutes)).forEach {
            debtRepository.markDueReminderSent(it.debt.id, now)
            reminders += DebtReminder(DebtReminderKind.DUE_SOON, it)
        }
        debtRepository.findOverdue(now, now.minusDays(OVERDUE_REPEAT_DAYS)).forEach {
            debtRepository.markOverdueReminded(it.debt.id, now)
            reminders += DebtReminder(DebtReminderKind.OVERDUE, it)
        }
        debtRepository.findPromiseDue(LocalDate.now(MSK)).forEach {
            debtRepository.markPromiseReminded(it.debt.id, now)
            reminders += DebtReminder(DebtReminderKind.PROMISE_DUE, it)
        }
        debtRepository.findClaimedStale(now.minusHours(claimStaleHours), now.minusDays(CLAIM_REPEAT_DAYS)).forEach {
            debtRepository.markClaimReminded(it.debt.id, now)
            reminders += DebtReminder(DebtReminderKind.CLAIM_STALE, it)
        }
        // Перед −40: за неделю и за день до момента списания (PO 2026-09-13). Обещание их не глушит:
        // несдержанное обещание иначе молчало бы до самого минуса.
        val penaltyOffset = now.minusWeeks(overdueWeeks)
        val warnedThisTick = mutableSetOf<UUID>()
        debtRepository.findMinusWarningDue(now, penaltyOffset.plusDays(MINUS_WEEK_WARNING_DAYS), dayWarning = false).forEach {
            debtRepository.markMinusWarned(it.debt.id, now, dayWarning = false)
            warnedThisTick += it.debt.id
            reminders += DebtReminder(DebtReminderKind.MINUS_IN_WEEK, it)
        }
        debtRepository.findMinusWarningDue(now, penaltyOffset.plusDays(MINUS_DAY_WARNING_DAYS), dayWarning = true)
            .filter { it.debt.id !in warnedThisTick }
            .forEach {
                debtRepository.markMinusWarned(it.debt.id, now, dayWarning = true)
                reminders += DebtReminder(DebtReminderKind.MINUS_TOMORROW, it)
            }
        return reminders
    }

    /** Неразобранные сальдо старше `claim-stale-hours`: получателю напоминание раз в 3 дня, штамп до отправки. */
    @Transactional
    fun collectStaleSettlements(now: OffsetDateTime): List<DebtSettlement> {
        val stale = debtRepository.findStaleSettlements(now.minusHours(claimStaleHours), now.minusDays(CLAIM_REPEAT_DAYS))
        stale.forEach { debtRepository.markSettlementReminded(it.id, now) }
        return stale
    }

    companion object {
        private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
        // Просрочка: напоминание в день срока и затем раз в 7 дней.
        private const val OVERDUE_REPEAT_DAYS = 7L
        // «Отдал» без ответа: получателю раз в 3 дня.
        private const val CLAIM_REPEAT_DAYS = 3L
        // Предупреждения перед −40: за 7 дней и за 1 день до момента списания.
        private const val MINUS_WEEK_WARNING_DAYS = 7L
        private const val MINUS_DAY_WARNING_DAYS = 1L
    }
}
