package com.clubs.bot

import com.clubs.chatlink.ChatDoorService
import com.clubs.membership.MembershipAccessOpenedEvent
import com.clubs.membership.MembershipAccessRevokedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Чат-«дверь» (club-chat-link): реагирует на открытие/закрытие доступа к клубу ПОСЛЕ коммита
 * исходной транзакции — как EventBotNotifier/Stage2StartedListener, AFTER_COMMIT гарантирует,
 * что откат мутации membership не оставит человека впущенным в чат. Сами действия —
 * @Async в [ChatDoorService] (Telegram-вызовы вне request-потока), best-effort.
 */
@Component
class ChatDoorListener(
    private val chatDoorService: ChatDoorService
) {
    private val log = LoggerFactory.getLogger(ChatDoorListener::class.java)

    // fallbackExecution: публикация вне активной транзакции (например, из будущего кода без
    // @Transactional) не должна молча терять событие — как у EventBotNotifier.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAccessOpened(event: MembershipAccessOpenedEvent) {
        log.info("Chat door: access opened, dispatching: clubId={} userId={}", event.clubId, event.userId)
        chatDoorService.onAccessOpened(event.clubId, event.userId)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAccessRevoked(event: MembershipAccessRevokedEvent) {
        log.info("Chat door: access revoked, dispatching: clubId={} userId={}", event.clubId, event.userId)
        chatDoorService.onAccessRevoked(event.clubId, event.userId)
    }
}
