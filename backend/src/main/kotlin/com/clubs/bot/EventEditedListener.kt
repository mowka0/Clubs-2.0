package com.clubs.bot

import com.clubs.chatlink.LivePinService
import com.clubs.event.EventEditedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Редактирование встречи на Этапе 1 (перенос даты и/или смена места) + маршрутизатор рассылок
 * (PO 2026-07-08): сначала чат (громкий пост «было → стало» + dirty-флаг закрепа через
 * [LivePinService.onEventEdited]), затем DM только тем участникам, которых пост не покрыл
 * ([ChatAwareBroadcast]). Последовательность в одном @Async-потоке — решение «кому DM»
 * зависит от факта выхода поста. AFTER_COMMIT — DM/пост читают уже закоммиченное состояние.
 * Best-effort: ошибки доставки глотаются внутри шлюза/sendDm. Зеркалит EventCancelledListener.
 *
 * Событие публикуется только при критичных изменениях («где»/«когда»), поэтому здесь
 * дополнительная фильтрация не нужна — см. [EventEditedEvent.hasCriticalChanges].
 */
@Component
class EventEditedListener(
    private val notificationService: NotificationService,
    private val livePinService: LivePinService
) {

    private val log = LoggerFactory.getLogger(EventEditedListener::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventEdited(event: EventEditedEvent) {
        val chatPostChatId = livePinService.onEventEdited(event)
        log.info(
            "Event {} edited — notifying members, chatPost={} datetimeChanged={} locationChanged={}",
            event.event.id, chatPostChatId != null, event.isDatetimeChanged, event.isLocationChanged
        )
        notificationService.sendEventEdited(event, chatPostChatId)
    }
}
