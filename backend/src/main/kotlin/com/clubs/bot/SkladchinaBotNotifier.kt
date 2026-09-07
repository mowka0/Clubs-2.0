package com.clubs.bot

import com.clubs.chatlink.SkladchinaChatStatusService
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.skladchina.SkladchinaClosedEvent
import com.clubs.skladchina.SkladchinaCreatedEvent
import com.clubs.skladchina.SkladchinaDeclineRejectedEvent
import com.clubs.skladchina.SkladchinaConfirmationRequestedEvent
import com.clubs.skladchina.SkladchinaDeclineRequestedEvent
import com.clubs.skladchina.SkladchinaPaymentDisputeResolvedEvent
import com.clubs.skladchina.SkladchinaPaymentDisputedEvent
import com.clubs.skladchina.SkladchinaPaymentRejectedEvent
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.format.DateTimeFormatter

/**
 * Отправка DM ботом по жизненному циклу складчины. Слушает доменные события,
 * публикуемые SkladchinaService, и шлёт DM ПОСЛЕ коммита исходной транзакции
 * (`@TransactionalEventListener` фаза по умолчанию = AFTER_COMMIT).
 *
 * Тот же паттерн, что EventBotNotifier — проверенный способ «отправить DM после
 * успешной мутации в БД». Создание складчины дополнительно оркестрирует чат-пост
 * (маршрутизатор рассылок, PO 2026-07-08): сначала живой статус в чат, затем DM
 * только тем участникам, кого пост не покрыл ([ChatAwareBroadcast]).
 */
@Component
class SkladchinaBotNotifier(
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val skladchinaChatStatusService: SkladchinaChatStatusService,
    private val chatAwareBroadcast: ChatAwareBroadcast
) {
    private val log = LoggerFactory.getLogger(SkladchinaBotNotifier::class.java)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    // @Async: пост в чат + N getChatMember + DM-цикл — Telegram I/O не место на потоке коммита.
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaCreated(event: SkladchinaCreatedEvent) {
        // Сначала чат: решение «кому DM» зависит от ФАКТА выхода поста — шаги последовательны.
        val chatPostChatId = skladchinaChatStatusService.onSkladchinaCreated(event.clubId, event.skladchinaId)
        val telegramIds = chatAwareBroadcast.dmTargets(
            chatPostChatId, userRepository.findTelegramIds(event.participantUserIds)
        )
        log.info("Skladchina-created DM: id={} participants={} chatPost={} dmTargets={}",
            event.skladchinaId, event.participantUserIds.size, chatPostChatId != null, telegramIds.size)
        if (telegramIds.isEmpty()) {
            log.info("Skladchina-created DM SKIPPED — all covered by chat or no telegramIds, skladchina={}", event.skladchinaId)
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
            if (event.affectsReputation) {
                // Штраф -40 за молчание легитимен только если условия были объявлены заранее
                // (launch-blocker редизайна, уведомление #1).
                append("\n\n⚠️ Важный сбор: оплата +10, отказ — без штрафа, молчание до дедлайна −40")
            }
            append("\n\n💳 Платёжная ссылка:\n").append(event.paymentLink)
            append("\n\nПосле оплаты — отметьте в приложении, чтобы организатор увидел.")
        }

        // WebApp inline button c прямым frontend URL — открывает Mini App
        // на /skladchina/<id>, React Router рендерит SkladchinaPage напрямую.
        val webAppPath = "/skladchina/${event.skladchinaId}"
        telegramIds.forEach { telegramId ->
            notificationService.sendDirectMessageWithDeepLink(
                telegramId = telegramId,
                text = text,
                webAppPath = webAppPath,
                buttonText = "💰 Открыть сбор"
            )
        }
    }

    /**
     * #6: участник попросил освободить его от оплаты (шаблоны REQUIRES_APPROVAL). Отправляем DM
     * организатору с именем просителя и причиной, с кнопкой перехода на страницу складчины для решения.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onDeclineRequested(event: SkladchinaDeclineRequestedEvent) {
        val organizerTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (organizerTelegramId == null) {
            log.warn("Skladchina decline-request DM SKIPPED — organizer telegramId missing: id={}", event.skladchinaId)
            return
        }
        val requesterName = userRepository.findById(event.requesterUserId)?.firstName ?: "Участник"
        val text = buildString {
            append("🙅 $requesterName просит отказаться от оплаты")
            append("\nСбор «${event.title}»")
            if (event.clubName.isNotBlank()) append(" · клуб «${event.clubName}»")
            append("\n\nПричина: «${event.reason}»")
            append("\n\nОдобрите отказ или отклоните (с причиной) в приложении.")
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = organizerTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "💰 Открыть сбор"
        )
        log.info("Skladchina decline-request DM sent: id={} organizer={}", event.skladchinaId, organizerTelegramId)
    }

    /**
     * #7: организатор отклонил запрос на отказ от оплаты. Отправляем участнику DM с причиной
     * организатора и кнопкой на страницу складчины — платить всё равно нужно.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onDeclineRejected(event: SkladchinaDeclineRejectedEvent) {
        val participantTelegramId = userRepository.findById(event.participantUserId)?.telegramId
        if (participantTelegramId == null) {
            log.warn("Skladchina decline-rejected DM SKIPPED — participant telegramId missing: id={}", event.skladchinaId)
            return
        }
        val text = buildString {
            append("❌ Ваш запрос на отказ отклонён")
            append("\nСбор «${event.title}»")
            if (event.clubName.isNotBlank()) append(" · клуб «${event.clubName}»")
            append("\n\nПричина организатора: «${event.reason}»")
            append("\n\nНужно оплатить счёт.")
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = participantTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "💰 Открыть сбор"
        )
        log.info("Skladchina decline-rejected DM sent: id={} participant={}", event.skladchinaId, participantTelegramId)
    }

    /**
     * V89: сбор дождался всех ответов или своего срока — зовём организатора сверить деньги.
     * Это единственный сигнал, что пора закрывать сбор: сам он больше не закроется.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onConfirmationRequested(event: SkladchinaConfirmationRequestedEvent) {
        val organizerTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (organizerTelegramId == null) {
            log.warn("Skladchina confirmation-request DM SKIPPED — organizer telegramId missing: id={}", event.skladchinaId)
            return
        }
        val text = buildString {
            append("💰 Сбор «${event.title}» завершён — сверьте деньги")
            if (event.clubName.isNotBlank()) append("\nКлуб «${event.clubName}»")
            append("\n\nОплату заявили ${event.claimedCount} из ${event.participantCount}.")
            append("\nСнимите галку с тех, от кого платёж не дошёл, и закройте сбор.")
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = organizerTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "💰 Сверить оплаты"
        )
        log.info("Skladchina confirmation-request DM sent: id={} organizer={}", event.skladchinaId, organizerTelegramId)
    }

    /**
     * V89: организатор не нашёл платёж участника. У человека есть окно на чек, поэтому DM обязан
     * назвать и причину, и срок — иначе он узнает о минусе, когда тот уже списан.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun onPaymentRejected(event: SkladchinaPaymentRejectedEvent) {
        val participantTelegramId = userRepository.findById(event.participantUserId)?.telegramId
        if (participantTelegramId == null) {
            log.warn("Skladchina payment-rejected DM SKIPPED — participant telegramId missing: id={}", event.skladchinaId)
            return
        }
        val text = buildString {
            append("❗️ Организатор не нашёл вашу оплату")
            append("\nСбор «${event.title}»")
            if (event.clubName.isNotBlank()) append(" · клуб «${event.clubName}»")
            event.reason?.let { append("\n\nПричина: «$it»") }
            append("\n\nЕсли деньги ушли — приложите фото или скриншот чека до ")
            append(event.receiptDeadline.format(fmt)).append(".")
            if (event.affectsReputation) append("\nБез чека спишется 40 очков надёжности.")
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = participantTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "🧾 Приложить чек"
        )
        log.info("Skladchina payment-rejected DM sent: id={} participant={}", event.skladchinaId, participantTelegramId)
    }

    /** V89: участник прислал чек — организатору нужно его посмотреть и принять решение. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onPaymentDisputed(event: SkladchinaPaymentDisputedEvent) {
        val organizerTelegramId = userRepository.findById(event.creatorId)?.telegramId
        if (organizerTelegramId == null) {
            log.warn("Skladchina payment-disputed DM SKIPPED — organizer telegramId missing: id={}", event.skladchinaId)
            return
        }
        val disputerName = userRepository.findById(event.disputerUserId)?.firstName ?: "Участник"
        val text = buildString {
            append("🧾 $disputerName прислал чек по сбору «${event.title}»")
            if (event.clubName.isNotBlank()) append("\nКлуб «${event.clubName}»")
            event.note?.let { append("\n\nКомментарий: «$it»") }
            append("\n\nПосмотрите чек и решите: засчитать оплату или платежа нет.")
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = organizerTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "🧾 Посмотреть чек"
        )
        log.info("Skladchina payment-disputed DM sent: id={} organizer={}", event.skladchinaId, organizerTelegramId)
    }

    /** V89: организатор разобрал чек — участник узнаёт исход из ЛС, а не из своего профиля. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onPaymentDisputeResolved(event: SkladchinaPaymentDisputeResolvedEvent) {
        val participantTelegramId = userRepository.findById(event.participantUserId)?.telegramId
        if (participantTelegramId == null) {
            log.warn("Skladchina dispute-resolved DM SKIPPED — participant telegramId missing: id={}", event.skladchinaId)
            return
        }
        val text = buildString {
            if (event.accepted) {
                append("✅ Оплата подтверждена — сбор «${event.title}»")
                if (event.affectsReputation) append("\n\n+10 к надёжности в клубе «${event.clubName}».")
            } else {
                append("❌ Организатор не подтвердил оплату — сбор «${event.title}»")
                append("\n\nЧек не сошёлся с выпиской.")
                if (event.affectsReputation) append(" Списано 40 очков надёжности.")
            }
        }
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = participantTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "💰 Открыть сбор"
        )
        log.info("Skladchina dispute-resolved DM sent: id={} participant={} accepted={}",
            event.skladchinaId, participantTelegramId, event.accepted)
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun onSkladchinaClosed(event: SkladchinaClosedEvent) {
        notifyExpiredParticipants(event)

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
            if (event.closedWithoutConfirmation) {
                append("\n\nВы не сверили оплаты, поэтому сбор закрыт без последствий: ")
                append("репутация никому не начислена и не снижена.")
            } else if (event.affectsReputation) {
                append("\n⚠️ Репутация участников пересчитана.")
            }
        }

        // Deep-link ведёт прямо на саму складчину, а не на корень приложения — организатор
        // попадает на закрытый сбор (статусы участников, собранная сумма), как во всех
        // остальных DM по складчине (фидбек со staging 2026-06-12).
        notificationService.sendDirectMessageWithDeepLink(
            telegramId = creatorTelegramId,
            text = text,
            webAppPath = "/skladchina/${event.skladchinaId}",
            buttonText = "💰 Открыть сбор"
        )
        log.info("Skladchina-closed DM sent: id={} status={} creator={}",
            event.skladchinaId, event.finalStatus, creatorTelegramId)
    }

    /**
     * Уведомление #3 из тройки launch-blocker'ов редизайна (DM с суммой → напоминание за 24ч →
     * отчёт по штрафу): каждый, кто получил -40, должен быть уведомлён явно, а не узнать об этом
     * случайно из своего профиля. [SkladchinaClosedEvent.expiredParticipantUserIds] непусто
     * только при закрытии, влияющем на репутацию, в момент/после дедлайна.
     */
    private fun notifyExpiredParticipants(event: SkladchinaClosedEvent) {
        if (event.expiredParticipantUserIds.isEmpty()) return
        val telegramIds = userRepository.findTelegramIds(event.expiredParticipantUserIds)
        log.info("Skladchina-expired DM: id={} expired={} resolved telegramIds={}",
            event.skladchinaId, event.expiredParticipantUserIds.size, telegramIds.size)
        val text = "⚠️ Сбор «${event.title}» в клубе «${event.clubName}» закрыт.\n\n" +
            "Вы не ответили на важный сбор до дедлайна — репутация снижена на 40."
        telegramIds.forEach { telegramId ->
            notificationService.sendDirectMessageWithDeepLink(
                telegramId = telegramId,
                text = text,
                webAppPath = "/skladchina/${event.skladchinaId}",
                buttonText = "💰 Открыть сбор"
            )
        }
    }
}
