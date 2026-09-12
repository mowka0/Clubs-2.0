package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.debt.DebtReminderService
import com.clubs.debt.DebtRepository
import com.clubs.debt.DebtReputationService
import com.clubs.generated.jooq.enums.DebtStatus
import com.clubs.skladchina.SkladchinaLifecycleService
import com.clubs.skladchina.SkladchinaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Один шедулер сборов и долгов (skladchina-v3 § 5) с периодом `debts.poll-ms`: заморозка записи
 * по сроку, напоминания создателю per_head, репутация (+10 / −40), напоминания должникам и
 * получателям, напоминание о сроке в чат клуба. Каждый шаг в своём try/catch — сбой одного не
 * останавливает остальные. Все шаги идемпотентны по штампам в БД (ставятся до отправки DM).
 */
@Component
class DebtScheduler(
    private val lifecycleService: SkladchinaLifecycleService,
    private val reputationService: DebtReputationService,
    private val reminderService: DebtReminderService,
    private val skladchinaRepository: SkladchinaRepository,
    private val debtRepository: DebtRepository,
    private val chatStatusService: SkladchinaChatStatusService,
    private val notifier: DebtBotNotifier,
    @Value("\${skladchinas.deadline-reminder-minutes-before:1440}") private val deadlineReminderMinutesBefore: Long
) {
    private val log = LoggerFactory.getLogger(DebtScheduler::class.java)

    @Scheduled(fixedDelayString = "\${debts.poll-ms:600000}")
    fun tick() {
        val now = OffsetDateTime.now()
        step("lock-enrollments") {
            lifecycleService.findEnrollmentDueIds(now).forEach { id ->
                try {
                    lifecycleService.lockBySchedule(id)
                } catch (e: Exception) {
                    log.error("Enrollment lock failed: skladchinaId={}", id, e)
                }
            }
        }
        step("order-reminders") {
            lifecycleService.claimOrderReminders(now).forEach { notifier.sendOrderReminder(it, debtRepository.totals(it.id)) }
        }
        step("reputation") {
            reputationService.applyPlus(now)
            reputationService.applyMinus(now).forEach(notifier::sendPenalty)
        }
        step("debt-reminders") {
            reminderService.collect(now).forEach(notifier::sendReminder)
            reminderService.collectStaleSettlements(now).forEach(notifier::sendSettlementReminder)
        }
        step("chat-deadline-reminders") { chatDeadlineReminders(now) }
    }

    /**
     * За 24 часа до срока сбора — в чат клуба с упоминаниями ещё не оплативших. Личное «завтра
     * срок» каждому должнику шлёт шаг debt-reminders (DUE_SOON), поэтому DM-фоллбека здесь нет —
     * иначе должник вне чата получал бы два одинаковых DM. Штамп на сборе ставится ДО отправки.
     */
    private fun chatDeadlineReminders(now: OffsetDateTime) {
        skladchinaRepository.findNeedingDeadlineReminder(now, now.plusMinutes(deadlineReminderMinutesBefore)).forEach { s ->
            skladchinaRepository.markReminderSent(s.id, now)
            val pending = debtRepository.findBySkladchina(s.id)
                .filter { (it.debt.status == DebtStatus.waiting || it.debt.status == DebtStatus.promised) && it.debt.debtorId != it.debt.creditorId }
                .map { it.debt.debtorId }
            if (pending.isEmpty()) return@forEach
            val covered = chatStatusService.postDeadlineReminder(s, pending)
            log.info("Skladchina deadline reminder: id={} pending={} mentionedInChat={}", s.id, pending.size, covered.size)
        }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("DebtScheduler step '{}' failed", name, e)
        }
    }
}
