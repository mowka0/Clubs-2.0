package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.EventCancelledEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * F5-14 + маршрутизатор рассылок (PO 2026-07-08): при отмене события — сначала чат
 * (тихая правка закрепа + громкий пост об отмене через [LivePinService.onEventCancelled]),
 * затем DM об отмене только участникам, которых пост не покрыл ([ChatAwareBroadcast]).
 * Последовательность в одном @Async-потоке — решение «кому DM» зависит от факта выхода поста.
 * AFTER_COMMIT — DM/пост читают уже закоммиченную отмену. Best-effort — ошибки доставки
 * глотаются внутри шлюза/sendDm.
 */
@Component
class EventCancelledListener(
    private val notificationService: NotificationService,
    private val livePinService: LivePinService
) {

    private val log = LoggerFactory.getLogger(EventCancelledListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventCancelled(event: EventCancelledEvent) {
        val chatPostChatId = livePinService.onEventCancelled(event.event, event.reason)
        log.info("Event {} cancelled — notifying members, chatPost={}", event.event.id, chatPostChatId != null)
        notificationService.sendEventCancelled(event.event, event.reason, chatPostChatId)
    }
}
