package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.common.util.Money
import com.clubs.generated.jooq.enums.SkladchinaKind
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.reputation.ReputationPolicy
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaCreatedEvent
import com.clubs.skladchina.SkladchinaLockedEvent
import com.clubs.skladchina.SkladchinaOrderedEvent
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * DM по жизненному циклу сбора (skladchina-v3 § 6): создание по виду, заморозка списка «Кто в деле?»,
 * «Заказываю», итог. Слушает доменные события ПОСЛЕ коммита (`@TransactionalEventListener`).
 * Создание дополнительно оркестрирует чат-пост: сначала живой статус в чат, затем DM только
 * тем, кого пост не покрыл ([ChatAwareBroadcast]); тихий сбор в чат не идёт.
 */
@Component
class SkladchinaBotNotifier(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val skladchinaChatStatusService: SkladchinaChatStatusService,
    private val chatAwareBroadcast: ChatAwareBroadcast
) {
    private val log = LoggerFactory.getLogger(SkladchinaBotNotifier::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'").withZone(ZoneId.of("Europe/Moscow"))

    // @Async: пост в чат + N getChatMember + DM-цикл — Telegram I/O не место на потоке коммита.
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaCreated(event: SkladchinaCreatedEvent) {
        // Сначала чат: решение «кому DM» зависит от ФАКТА выхода поста — шаги последовательны.
        val chatPostChatId = if (event.hiddenFromUserId == null) {
            skladchinaChatStatusService.onSkladchinaCreated(event.clubId, event.skladchinaId)
        } else null
        val recipients = userRepository.findByIds(event.recipientUserIds)
        val dmTelegramIds = chatAwareBroadcast.dmTargets(chatPostChatId, recipients.map { it.telegramId }).toSet()
        log.info("Skladchina-created DM: id={} kind={} recipients={} chatPost={} dmTargets={}",
            event.skladchinaId, event.kind, recipients.size, chatPostChatId != null, dmTelegramIds.size)

        val creatorName = userRepository.findById(event.creatorId)?.firstName ?: "Организатор"
        recipients.filter { it.telegramId in dmTelegramIds }.forEach { user ->
            val text = createdText(event, creatorName, share = event.debtorShares[user.id])
            notificationService.sendDirectMessageWithDeepLink(user.telegramId, text, "/skladchina/${event.skladchinaId}", OPEN_BUTTON)
        }
    }

    private fun createdText(e: SkladchinaCreatedEvent, creatorName: String, share: Long?): String = buildString {
        when (e.kind) {
            SkladchinaKind.shared -> {
                append("💰 Сбор «${e.title}» в клубе «${e.clubName}»")
                e.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(200)) }
                if (share != null) {
                    append("\n\n💵 Ваша доля: ").append(Money.rub(share))
                    e.deadline?.let { append("\n⏳ До: ").append(it.format(fmt)) }
                    append(requisites(e.paymentLink, e.paymentMethodNote))
                    append("\n\nПосле перевода нажмите «Отдал» — $creatorName подтвердит.")
                } else {
                    append("\n\n").append(Money.rub(e.amountKopecks ?: 0L)).append(" на группу, поровну между теми, кто в деле.")
                    e.enrollmentUntil?.let { append("\n⏳ Отметиться до ").append(it.format(fmt)) }
                }
                append("\n\n").append(ReputationPolicy.skladchinaRulesLine())
            }
            SkladchinaKind.per_head -> {
                append("🎫 «${e.title}» в клубе «${e.clubName}»: ").append(Money.rub(e.amountKopecks ?: 0L)).append(" за штуку.")
                e.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(200)) }
                e.deadline?.let { append("\n\n$creatorName покупает ").append(it.format(fmt)).append(" на тех, кто оплатил.") }
                append("\nНажмите «Беру», если вам нужно.")
            }
            SkladchinaKind.voluntary -> {
                append("🎁 «${e.title}» в клубе «${e.clubName}» — по желанию")
                e.amountKopecks?.let { append(", ориентир ").append(Money.rub(it)) }
                e.deadline?.let { append(", до ").append(it.format(fmt)) }
                append(". Собирает $creatorName.")
                e.description?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.take(200)) }
                append(requisites(e.paymentLink, e.paymentMethodNote))
                append("\n\nПеревели — нажмите «Перевёл» в приложении.")
            }
        }
    }

    /** Список заморожен: должникам их доля, создателю итог; не набрали — только создателю. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onLocked(event: SkladchinaLockedEvent) {
        val creatorTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (event.cancelledForShortfall) {
            creatorTelegramId?.let {
                notificationService.sendDirectMessageWithDeepLink(
                    it,
                    "Не набрали: в деле ${event.enrolledCount}" + (event.minParticipants?.let { m -> " из $m" } ?: "") +
                        ". Сбор «${event.title}» отменён, денег никто не переводил.",
                    "/skladchina/${event.skladchinaId}", OPEN_BUTTON
                )
            }
            return
        }
        val share = event.debtorShares.values.firstOrNull()
        val creatorName = userRepository.findById(event.creatorId)?.firstName ?: "Организатор"
        userRepository.findByIds(event.debtorShares.keys).forEach { user ->
            val amount = event.debtorShares[user.id] ?: return@forEach
            val text = buildString {
                append("💰 Список «${event.title}» заморожен: в деле ${event.enrolledCount}.")
                append("\n\n💵 Ваша доля: ").append(Money.rub(amount))
                event.deadline?.let { append("\n⏳ До: ").append(it.format(fmt)) }
                append(requisites(event.paymentLink, event.paymentMethodNote))
                append("\n\nПосле перевода нажмите «Отдал» — $creatorName подтвердит.")
                append("\n\n").append(ReputationPolicy.skladchinaRulesLine())
            }
            notificationService.sendDirectMessageWithDeepLink(user.telegramId, text, "/skladchina/${event.skladchinaId}", OPEN_BUTTON)
        }
        creatorTelegramId?.let {
            notificationService.sendDirectMessageWithDeepLink(
                it,
                "👥 «${event.title}»: в деле ${event.enrolledCount}, список заморожен" +
                    (share?.let { s -> ", по ${Money.rub(s)} с человека" } ?: "") + ".",
                "/skladchina/${event.skladchinaId}", OPEN_BUTTON
            )
        }
        log.info("Skladchina-locked DM sent: id={} debtors={}", event.skladchinaId, event.debtorShares.size)
    }

    /** «Заказываю»: выбывшим без долга. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onOrdered(event: SkladchinaOrderedEvent) {
        if (event.droppedUserIds.isEmpty()) return
        val text = "🛒 «${event.title}» в клубе «${event.clubName}»: заказ сделан. " +
            "Вы не оплатили до заказа — вы выбыли, долга нет."
        userRepository.findTelegramIds(event.droppedUserIds).forEach {
            notificationService.sendDirectMessageWithDeepLink(it, text, "/skladchina/${event.skladchinaId}", OPEN_BUTTON)
        }
        log.info("Skladchina-ordered DM sent: id={} dropped={}", event.skladchinaId, event.droppedUserIds.size)
    }

    /** Итог создателю: собран — суммы; отменён — кому вернуть уже полученное. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaClosed(event: SkladchinaClosedEvent) {
        val creatorTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (creatorTelegramId == null) {
            log.warn("Skladchina-closed DM SKIPPED — creator telegramId missing: id={}", event.skladchinaId)
            return
        }
        val text = when (event.finalStatus) {
            SkladchinaStatus.collected -> buildString {
                append("✅ Сбор «${event.title}» собран")
                append("\n\nПолучено: ").append(Money.rub(event.receivedKopecks))
                event.targetKopecks?.let { append(" из ").append(Money.rub(it)) }
                if (event.kind == SkladchinaKind.per_head) append("\nКуплено: ${event.receivedCount}")
                else append("\nОплатили: ${event.receivedCount} из ${event.debtCount}")
            }
            else -> buildString {
                append("🚫 Сбор «${event.title}» отменён.")
                if (event.refunds.isNotEmpty()) {
                    val names = userRepository.findByIds(event.refunds.keys).associateBy { it.id!! }
                    append("\n\nКому вернуть:")
                    event.refunds.forEach { (userId, amount) ->
                        append("\n• ").append(names[userId]?.firstName ?: "Участник").append(" — ").append(Money.rub(amount))
                    }
                }
            }
        }
        notificationService.sendDirectMessageWithDeepLink(creatorTelegramId, text, "/skladchina/${event.skladchinaId}", OPEN_BUTTON)
        log.info("Skladchina-closed DM sent: id={} status={} creator={}", event.skladchinaId, event.finalStatus, creatorTelegramId)
    }

    private fun requisites(paymentLink: String, note: String?): String = buildString {
        append("\n\n💳 Реквизиты:\n").append(paymentLink)
        note?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
    }

    companion object {
        const val OPEN_BUTTON = "💰 Открыть сбор"
    }
}
