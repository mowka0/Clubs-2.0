package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
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
 * Поллинг-напоминание о дедлайне для складчин, влияющих на репутацию (модуль bot =
 * слой уведомлений; зависит от `skladchina`). Паттерн: EventReminderScheduler.
 *
 * Примерно за `deadline-reminder-minutes-before` (по умолчанию 24ч) до дедлайна шлём DM
 * каждому ещё не ответившему участнику: оплатить или отказаться, молчание стоит -40.
 * Это уведомление #2 из троицы launch-blocker'ов редизайна (DM с ценой при создании →
 * это напоминание → отчёт о штрафе) — без него штраф -40 нелегитимен.
 *
 * Намеренно НЕТ @Transactional на цикле: `markReminderSent` — независимый автокоммитящийся
 * UPDATE, выполняемый ДО отправки DM, поэтому повторяющийся поллинг никогда не отправит дважды;
 * DM, который затем падает, логируется и отбрасывается (доставка best-effort, как и любой
 * другой DM в NotificationService).
 */
@Component
class SkladchinaReminderScheduler(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val chatStatusService: SkladchinaChatStatusService,
    @Value("\${skladchinas.deadline-reminder-minutes-before:1440}") private val reminderMinutesBefore: Long
) {
    private val log = LoggerFactory.getLogger(SkladchinaReminderScheduler::class.java)

    // МСК, как в чат-статусе сбора (SkladchinaChatStatusRenderer): дедлайн в БД — UTC, и без
    // явной зоны DM показывал бы «15:00» там, где чат-напоминание говорит «18:00 МСК».
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'")
        .withZone(java.time.ZoneId.of("Europe/Moscow"))

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
        // Слайс 3.5 «живой статус сбора»: напоминание уходит в ЧАТ клуба с упоминаниями
        // (гарантированный канал — DM доходит только после /start у бота). DM остаётся
        // фоллбеком для тех, кого в чате нет; пустой сет = чат недоступен, DM всем.
        val coveredByChat = chatStatusService.postDeadlineReminder(skladchina, pendingUserIds)
        val dmTargets = pendingUserIds.filterNot { it in coveredByChat }
        if (dmTargets.isEmpty()) {
            log.info("Deadline reminder fully covered by chat mention: skladchinaId={}", skladchina.id)
            return
        }
        val telegramIds = userRepository.findTelegramIds(dmTargets)
        log.info("Deadline reminder DM: skladchinaId={} pending={} coveredByChat={} resolved telegramIds={}",
            skladchina.id, pendingUserIds.size, coveredByChat.size, telegramIds.size)

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
