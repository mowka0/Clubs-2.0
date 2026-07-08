package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.EventCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Оркестратор уведомления «событие создано» (маршрутизатор рассылок, решение PO 2026-07-08):
 * сначала пост живого закрепа в чат (если включён), затем DM — только тем участникам клуба,
 * кого пост не покрыл ([ChatAwareBroadcast]). Порядок обязателен: решение «кому DM» зависит
 * от ФАКТА выхода поста, поэтому оба шага идут последовательно в одном @Async-потоке,
 * а не двумя независимыми листенерами (гонка дала бы двойной пинг).
 *
 * AFTER_COMMIT (@TransactionalEventListener) — если транзакция createEvent откатится,
 * листенер пропускается. @Async — Telegram I/O (пост + N getChatMember + DM) не должен
 * жить на потоке запроса. Ошибки Telegram по отдельным шагам ловятся ниже; доставка —
 * fire-and-forget by design: сбой Telegram не должен завалить создание события.
 */
@Component
class EventBotNotifier(
    private val notificationService: NotificationService,
    private val livePinService: LivePinService
) {
    private val log = LoggerFactory.getLogger(EventBotNotifier::class.java)

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    fun onEventCreated(created: EventCreatedEvent) {
        val chatPostChatId = livePinService.onEventCreated(created.event)
        log.info(
            "Event-created dispatch: eventId={} clubId={} chatPost={}",
            created.event.id, created.event.clubId, chatPostChatId != null
        )
        notificationService.sendEventCreated(created.event, chatPostChatId)
    }
}
