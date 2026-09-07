package com.clubs.subscription

import com.clubs.chatlink.ChatIdMigratedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Переезд группы в супергруппу (platform-billing.md § 5.3): признак бесплатной встречи едет за
 * новым chat_id. Синхронный слушатель — переносится в той же транзакции, что и привязка.
 */
@Component
class ChatIdMigratedListener(private val freeMeetingRepository: FreeMeetingRepository) {

    @EventListener
    fun onChatIdMigrated(event: ChatIdMigratedEvent) {
        freeMeetingRepository.migrateChatId(event.oldChatId, event.newChatId)
    }
}
