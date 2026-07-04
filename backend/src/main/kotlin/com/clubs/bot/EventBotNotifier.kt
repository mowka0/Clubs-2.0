package com.clubs.bot

import com.clubs.event.EventCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * DM-нотификатор бота о создании события. Слушает [EventCreatedEvent], публикуемый
 * EventService, и уведомляет участников клуба ПОСЛЕ коммита исходной транзакции
 * (`@TransactionalEventListener`, фаза по умолчанию = AFTER_COMMIT). Как и в
 * SkladchinaBotNotifier / SkladchinaBotNotifier, срабатывание AFTER_COMMIT —
 * проверенный путь для «отправить DM после успешной мутации в БД»: если транзакция
 * createEvent откатится, листенер пропускается.
 *
 * В отличие от тех хендлеров (шлют небольшое фиксированное число DM синхронно),
 * у события получателей может быть много, поэтому рассылка делегируется @Async
 * [NotificationService.sendEventCreated]: листенер возвращается сразу, а цикл
 * отправки крутится вне request-потока (правило бэкенда: массовые уведомления —
 * через @Async). Ошибки Telegram по отдельным DM ловятся и логируются внутри
 * sendEventCreated; доставка — fire-and-forget by design: сбой Telegram не должен
 * завалить создание события.
 */
@Component
class EventBotNotifier(
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(EventBotNotifier::class.java)

    @TransactionalEventListener(fallbackExecution = true)
    fun onEventCreated(created: EventCreatedEvent) {
        log.info("Event-created DM dispatch: eventId={} clubId={}", created.event.id, created.event.clubId)
        notificationService.sendEventCreated(created.event)
    }
}
