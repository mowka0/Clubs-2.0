package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.EventRescheduledEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Перенос даты события (только Этап 1) + маршрутизатор рассылок (PO 2026-07-08): сначала чат
 * (громкий пост «было → стало» + dirty-флаг закрепа через [LivePinService.onEventRescheduled]),
 * затем DM о переносе только участникам, которых пост не покрыл ([ChatAwareBroadcast]).
 * Последовательность в одном @Async-потоке — решение «кому DM» зависит от факта выхода поста.
 * AFTER_COMMIT — DM/пост читают уже закоммиченную новую дату. Best-effort — ошибки доставки
 * глотаются внутри шлюза/sendDm. Зеркалит EventCancelledListener.
 */
@Component
class EventRescheduledListener(
    private val notificationService: NotificationService,
    private val livePinService: LivePinService
) {

    private val log = LoggerFactory.getLogger(EventRescheduledListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventRescheduled(event: EventRescheduledEvent) {
        val chatPostChatId = livePinService.onEventRescheduled(event.event, event.oldDatetime)
        log.info("Event {} rescheduled — notifying members, chatPost={}", event.event.id, chatPostChatId != null)
        notificationService.sendEventRescheduled(event.event, event.oldDatetime, chatPostChatId)
    }
}
