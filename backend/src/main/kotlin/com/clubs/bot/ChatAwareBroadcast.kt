package com.clubs.bot

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Маршрутизатор клубных рассылок (решение PO 2026-07-08). Один инвариант:
 * «каждый получатель уведомляется ровно один раз — в чате, если пост туда реально вышел
 * и человек в чате; иначе — в личке».
 *
 * Опирается на ФАКТ доставки поста, а не на конфигурацию: клуб без чата, выключенный
 * тумблер, кикнутый бот, сбой Telegram — всё схлопывается в «поста нет» → DM всем,
 * ровно как до чат-интеграции. Членство в чате проверяется живьём на каждой рассылке
 * (getChatMember) — вступления/выходы самолечатся; «Telegram не ответил» считаем
 * «не в чате»: лишний DM лучше потерянного уведомления.
 *
 * Через маршрутизатор ходят только клубные broadcast'ы (событие создано/отменено,
 * складчина создана, складчина-напоминание). Персональные DM (заявки, подписка,
 * заморозка, споры, −40 и т.п.) — всегда личка, сюда не заходят.
 */
@Component
class ChatAwareBroadcast(
    private val gateway: ChatTelegramGateway
) {
    private val log = LoggerFactory.getLogger(ChatAwareBroadcast::class.java)

    /**
     * Кому из [telegramIds] слать DM. [chatPostChatId] — чат, куда фактически вышел пост
     * (null = поста нет → DM всем).
     */
    fun dmTargets(chatPostChatId: Long?, telegramIds: List<Long>): List<Long> {
        if (chatPostChatId == null) return telegramIds
        val targets = telegramIds.filter { gateway.getUserChatState(chatPostChatId, it) != UserChatState.IN_CHAT }
        log.info(
            "Broadcast routing: chatId={} recipients={} coveredByChat={} dmFallback={}",
            chatPostChatId, telegramIds.size, telegramIds.size - targets.size, targets.size
        )
        return targets
    }
}
