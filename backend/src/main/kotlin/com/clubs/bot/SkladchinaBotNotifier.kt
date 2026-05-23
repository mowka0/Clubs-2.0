package com.clubs.bot

import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.Skladchina
import com.clubs.skladchina.SkladchinaNotifier
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class SkladchinaBotNotifier(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) : SkladchinaNotifier {

    private val log = LoggerFactory.getLogger(SkladchinaBotNotifier::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    @Async
    override fun sendCreated(skladchina: Skladchina, clubName: String, participantUserIds: List<UUID>) {
        val telegramIds = userRepository.findTelegramIds(participantUserIds)
        val expectedNote = when (skladchina.paymentMode.literal) {
            "voluntary" -> "💵 Сумма: по желанию"
            else -> {
                val rubles = skladchina.totalGoalKopecks?.let { it / 100 } ?: 0
                "💵 Сумма сбора: $rubles ₽"
            }
        }
        val descSnippet = skladchina.description?.take(200)?.let { "\n\n$it" } ?: ""
        val text = """
            💰 Новый сбор в клубе «$clubName»: ${skladchina.title}$descSnippet

            $expectedNote
            ⏰ До: ${skladchina.deadline.format(fmt)}

            💳 Платёжная ссылка:
            ${skladchina.paymentLink}

            После оплаты — отметьте в приложении, чтобы организатор увидел.
        """.trimIndent()

        telegramIds.forEach { telegramId ->
            notificationService.sendDirectMessage(telegramId, text)
        }
        log.info("Skladchina-created DM sent: id={} recipients={}", skladchina.id, telegramIds.size)
    }

    @Async
    override fun sendClosed(
        skladchina: Skladchina,
        clubName: String,
        finalStatus: SkladchinaStatus,
        collectedKopecks: Long,
        paidCount: Int,
        participantCount: Int
    ) {
        val creatorTelegramId = userRepository.findById(skladchina.creatorId)?.telegramId
        if (creatorTelegramId == null) {
            log.warn("Skladchina-closed DM skipped — creator has no telegramId: skladchinaId={}", skladchina.id)
            return
        }
        val collectedRub = collectedKopecks / 100
        val goalLine = skladchina.totalGoalKopecks?.let { " из ${it / 100} ₽" } ?: ""
        val statusEmoji = when (finalStatus) {
            SkladchinaStatus.closed_success -> "✅"
            SkladchinaStatus.closed_failed -> "⚠️"
            SkladchinaStatus.cancelled -> "🚫"
            else -> "❓"
        }
        val reputationLine = if (skladchina.affectsReputation) "\n⚠️ Репутация участников пересчитана." else ""
        val text = """
            $statusEmoji Сбор закрыт: «${skladchina.title}»

            Собрано: $collectedRub ₽$goalLine
            Оплатили: $paidCount из $participantCount$reputationLine
        """.trimIndent()

        notificationService.sendDirectMessage(creatorTelegramId, text)
        log.info("Skladchina-closed DM sent: id={} status={} creator={}", skladchina.id, finalStatus, creatorTelegramId)
    }
}
