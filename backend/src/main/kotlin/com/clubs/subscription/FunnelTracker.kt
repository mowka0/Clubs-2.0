package com.clubs.subscription

import com.clubs.chatlink.ChatDisconnectedEvent
import com.clubs.chatlink.ChatLinkedEvent
import com.clubs.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Шаги привлечения (funnel.md § 3.1): `/start` в личке бота и судьба привязки чата. Биллинговые
 * шаги пишут сами BillingGate / BillingService — здесь только то, что до и вокруг них.
 * Привязка и отвязка приходят Spring-событиями из chatlink: обратная зависимость
 * (subscription → chatlink) уже есть, прямая замкнула бы цикл.
 */
@Service
class FunnelTracker(
    private val funnelEventRepository: FunnelEventRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(FunnelTracker::class.java)

    /**
     * «/start [payload]» в личке. Пользователя в users может ещё не быть (он появляется при первом
     * входе в Mini App) — шаг всё равно пишется, кампания сходится с ним по telegram id.
     */
    fun botStarted(telegramId: Long, commandText: String) {
        val campaign = parseCampaign(commandText)
        val userId = userRepository.findByTelegramId(telegramId)?.id
        funnelEventRepository.record(FunnelStep.BOT_STARTED, userId, clubId = null, campaign = campaign, telegramId = telegramId)
        // Сам payload — пользовательский ввод, в лог не попадает: только факт, принята ли метка.
        log.info("Funnel bot_started: telegramId={} campaign={}", telegramId, if (campaign != null) "accepted" else "none")
    }

    @EventListener
    fun onChatLinked(event: ChatLinkedEvent) {
        funnelEventRepository.record(FunnelStep.CHAT_CONNECTED, event.ownerUserId, event.clubId, telegramId = event.ownerTelegramId)
    }

    @EventListener
    fun onChatDisconnected(event: ChatDisconnectedEvent) {
        funnelEventRepository.record(FunnelStep.CHAT_DISCONNECTED, event.linkedByUserId, event.clubId)
    }

    companion object {
        // Рекламная метка: «/start ad_<slug>», slug — латиница, цифры, «_» и «-», до 64 знаков
        // (лимит payload'а у Telegram). Регистр не важен, хранится в строчных. Всё остальное — органика.
        private val CAMPAIGN_PAYLOAD = Regex("^ad_([a-z0-9_-]{1,64})$", RegexOption.IGNORE_CASE)

        /** Slug кампании из текста команды или null, если payload'а нет или он не по маске. */
        fun parseCampaign(commandText: String): String? {
            val payload = commandText.trim().substringAfter(' ', missingDelimiterValue = "").trim()
            return CAMPAIGN_PAYLOAD.matchEntire(payload)?.groupValues?.get(1)?.lowercase()
        }
    }
}
