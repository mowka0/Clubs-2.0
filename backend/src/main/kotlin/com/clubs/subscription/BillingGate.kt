package com.clubs.subscription

import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.Club
import com.clubs.common.exception.PaymentRequiredException
import com.clubs.common.exception.PaywallReason
import com.clubs.generated.jooq.enums.SubscriptionPlan
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Единственная точка биллинга за чат (platform-billing.md R4, § 6.4): стоит только на создании
 * встречи. Всё остальное — отмена, перенос, Этап 2, явка, закреп, складчина — работает при любом
 * статусе подписки («начатое доживает», R10).
 */
@Component
class BillingGate(
    private val chatLinkRepository: ChatLinkRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val chatTrialRepository: ChatTrialRepository,
    private val funnelEventRepository: FunnelEventRepository,
    // Грейс после конца оплаченного периода: всё разрешено, ждём оплату; потом — стена (R10).
    @Value("\${billing.grace-days:7}") private val graceDays: Long,
    // Бесплатный период чата от первой созданной встречи (решение PO 2026-09-15).
    @Value("\${billing.trial-days:15}") private val trialDays: Long,
) {

    private val log = LoggerFactory.getLogger(BillingGate::class.java)

    /**
     * Вызывается из EventService.createEvent ПОСЛЕ вставки события (нужен его id), в той же
     * транзакции — 402 откатывает вставку. Клуб без чата бесплатен (R2); оплаченный период
     * или грейс пропускают; иначе идёт бесплатный период чата, и стена встаёт лишь когда он
     * закончился. Первая встреча чата запускает отсчёт и всегда проходит.
     */
    fun requireBillable(club: Club, eventId: UUID, actorUserId: UUID) {
        // Бота выгнали из чата — привязка остаётся ради оживления (club-chat-link), но платить
        // за чат, где бота нет, не за что: ни стены, ни старта периода (PO 2026-09-16).
        val link = chatLinkRepository.findByClubId(club.id)?.takeIf { it.botStatus.isInChat } ?: return
        val now = OffsetDateTime.now()
        val subscription = subscriptionRepository.findLatestByClub(club.id)
        if (subscription != null && subscription.allowsNewMeetings(now, graceDays)) return

        val trial = chatTrialRepository.startOrGet(link.chatId, club.id, eventId)
        if (trial.justStarted) {
            log.info("Trial started: clubId={} chatId={} eventId={}", club.id, link.chatId, eventId)
            funnelEventRepository.record(FunnelStep.TRIAL_STARTED, actorUserId, club.id)
        }
        if (now.isBefore(trial.startedAt.plusDays(trialDays))) return

        val reason = if (subscription == null) PaywallReason.TRIAL_ENDED else PaywallReason.SUBSCRIPTION_EXPIRED
        log.info("Paywall shown: clubId={} reason={} userId={}", club.id, reason, actorUserId)
        // В отдельной транзакции: 402 ниже откатит текущую вместе со вставкой события.
        funnelEventRepository.recordDetached(FunnelStep.PAYWALL_SEEN, actorUserId, club.id)
        throw PaymentRequiredException(reason, club.id, subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT))
    }
}
