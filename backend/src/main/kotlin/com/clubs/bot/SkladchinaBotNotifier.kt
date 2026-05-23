package com.clubs.bot

import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaCreatedEvent
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.format.DateTimeFormatter

/**
 * Bot DM notifier for skladchina lifecycle. Listens to domain events published
 * by SkladchinaService and sends DMs AFTER the originating transaction has
 * committed (`@TransactionalEventListener` default phase = AFTER_COMMIT).
 *
 * Same pattern as PaymentNotificationHandler — proven path for "send DM after
 * DB mutation succeeded". Avoids the @Async + @Transactional caveats that
 * previously left DMs un-sent.
 */
@Component
class SkladchinaBotNotifier(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(SkladchinaBotNotifier::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaCreated(event: SkladchinaCreatedEvent) {
        val telegramIds = userRepository.findTelegramIds(event.participantUserIds)
        log.info("Skladchina-created DM: id={} participants={} resolved telegramIds={}",
            event.skladchinaId, event.participantUserIds.size, telegramIds.size)
        if (telegramIds.isEmpty()) {
            log.warn("Skladchina-created DM SKIPPED — no telegramIds resolved for skladchina={}", event.skladchinaId)
            return
        }

        val expectedNote = when (event.paymentMode) {
            "voluntary" -> "💵 Сумма: по желанию"
            else -> {
                val rubles = event.totalGoalKopecks?.let { it / 100 } ?: 0
                "💵 Сумма сбора: $rubles ₽"
            }
        }
        // buildString вместо """trimIndent""" — interpolated description с собственными
        // переводами строк ломал trimIndent calculation (минимальный indent становился 0,
        // остальные строки оставались с 12 пробелами отступа в финальной DM).
        val text = buildString {
            append("💰 Новый сбор в клубе «${event.clubName}»: ${event.title}")
            event.description?.takeIf { it.isNotBlank() }?.let {
                append("\n\n").append(it.take(200))
            }
            append("\n\n").append(expectedNote)
            append("\n⏰ До: ").append(event.deadline.format(fmt))
            append("\n\n💳 Платёжная ссылка:\n").append(event.paymentLink)
            append("\n\nПосле оплаты — отметьте в приложении, чтобы организатор увидел.")
        }

        // Deep-link inline button — открывает /skladchina/<id> в Mini App
        // через ?startapp=skladchina_<uuid> (см. DeepLinkHandler).
        val startApp = "skladchina_${event.skladchinaId}"
        telegramIds.forEach { telegramId ->
            notificationService.sendDirectMessageWithDeepLink(
                telegramId = telegramId,
                text = text,
                startApp = startApp,
                buttonText = "💰 Открыть сбор"
            )
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaClosed(event: SkladchinaClosedEvent) {
        val creatorTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (creatorTelegramId == null) {
            log.warn("Skladchina-closed DM SKIPPED — creator telegramId missing: id={}", event.skladchinaId)
            return
        }
        val collectedRub = event.collectedKopecks / 100
        val goalLine = event.totalGoalKopecks?.let { " из ${it / 100} ₽" } ?: ""
        val statusEmoji = when (event.finalStatus) {
            SkladchinaStatus.closed_success -> "✅"
            SkladchinaStatus.closed_failed -> "⚠️"
            SkladchinaStatus.cancelled -> "🚫"
            else -> "❓"
        }
        val text = buildString {
            append("$statusEmoji Сбор закрыт: «${event.title}»")
            append("\n\nСобрано: $collectedRub ₽$goalLine")
            append("\nОплатили: ${event.paidCount} из ${event.participantCount}")
            if (event.affectsReputation) {
                append("\n⚠️ Репутация участников пересчитана.")
            }
        }

        notificationService.sendDirectMessage(creatorTelegramId, text)
        log.info("Skladchina-closed DM sent: id={} status={} creator={}",
            event.skladchinaId, event.finalStatus, creatorTelegramId)
    }
}
