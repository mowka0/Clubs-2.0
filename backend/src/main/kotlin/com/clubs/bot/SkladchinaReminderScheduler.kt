package com.clubs.bot

import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Poll-based deadline reminder for reputation-affecting skladchinas (bot module =
 * notification layer; depends on `skladchina`). Pattern: EventReminderScheduler.
 *
 * ~`deadline-reminder-minutes-before` (default 24h) before the deadline, DM every
 * still-pending participant: pay or decline, silence costs -40. This is notification
 * #2 of the redesign's launch-blocker trio (creation price DM → this reminder →
 * penalty report) — without it the -40 penalty is not legitimate.
 *
 * Deliberately NO @Transactional on the loop: `markReminderSent` is an independent
 * auto-committed UPDATE set BEFORE the DMs, so a recurring poll never double-sends;
 * a DM that then fails is logged and dropped (delivery is best-effort, like every
 * other DM in NotificationService).
 */
@Component
class SkladchinaReminderScheduler(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    @Value("\${skladchinas.deadline-reminder-minutes-before:1440}") private val reminderMinutesBefore: Long
) {
    private val log = LoggerFactory.getLogger(SkladchinaReminderScheduler::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    @Scheduled(fixedDelayString = "\${skladchinas.reminder-poll-ms:300000}")
    fun remindPendingParticipants() {
        val now = OffsetDateTime.now()
        val needingReminder = skladchinaRepository.findNeedingDeadlineReminder(
            now, now.plusMinutes(reminderMinutesBefore)
        )
        needingReminder.forEach { skladchina ->
            try {
                skladchinaRepository.markReminderSent(skladchina.id, now)
                sendDeadlineReminder(skladchina)
            } catch (e: Exception) {
                log.error("Deadline reminder failed for skladchinaId={}", skladchina.id, e)
            }
        }
    }

    private fun sendDeadlineReminder(skladchina: Skladchina) {
        val pendingUserIds = skladchinaRepository.findParticipants(skladchina.id)
            .filter { it.status == SkladchinaParticipantStatus.pending }
            .map { it.userId }
        if (pendingUserIds.isEmpty()) {
            log.info("Deadline reminder SKIPPED — no pending participants for skladchinaId={}", skladchina.id)
            return
        }
        val telegramIds = userRepository.findTelegramIds(pendingUserIds)
        log.info("Deadline reminder DM: skladchinaId={} pending={} resolved telegramIds={}",
            skladchina.id, pendingUserIds.size, telegramIds.size)

        val clubName = clubRepository.findById(skladchina.clubId)?.name
        val text = buildString {
            append("⏰ Напоминание: сбор «${skladchina.title}»")
            clubName?.let { append(" в клубе «$it»") }
            append(" закрывается ").append(skladchina.deadline.format(fmt)).append(".")
            append("\n\nЭто важный сбор: оплатите или откажитесь до дедлайна — ")
            append("молчание снизит репутацию на 40.")
        }
        telegramIds.forEach { telegramId ->
            notificationService.sendDirectMessageWithDeepLink(
                telegramId = telegramId,
                text = text,
                webAppPath = "/skladchina/${skladchina.id}",
                buttonText = "💰 Открыть сбор"
            )
        }
    }
}
