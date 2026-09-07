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
    private val freeMeetingRepository: FreeMeetingRepository,
    private val funnelEventRepository: FunnelEventRepository,
    // Грейс после конца оплаченного периода: всё разрешено, ждём оплату; потом — стена (R10).
    @Value("\${billing.grace-days:7}") private val graceDays: Long,
) {

    private val log = LoggerFactory.getLogger(BillingGate::class.java)

    /**
     * Вызывается из EventService.createEvent ПОСЛЕ вставки события (нужен его id), в той же
     * транзакции — 402 откатывает вставку. Клуб без чата бесплатен (R2); оплаченный период
     * или грейс пропускают; иначе атомарно берётся бесплатная встреча чата, и лишь когда она
     * уже использована — стена.
     */
    fun requireBillable(club: Club, eventId: UUID, actorUserId: UUID) {
        val link = chatLinkRepository.findByClubId(club.id) ?: return
        val subscription = subscriptionRepository.findLatestByClub(club.id)
        if (subscription != null && subscription.allowsNewMeetings(OffsetDateTime.now(), graceDays)) return

        if (freeMeetingRepository.claim(link.chatId, club.id, eventId)) {
            log.info("Free meeting used: clubId={} chatId={} eventId={}", club.id, link.chatId, eventId)
            funnelEventRepository.record(FunnelStep.FREE_MEETING_USED, actorUserId, club.id)
            return
        }

        val reason = if (subscription == null) PaywallReason.FREE_MEETING_USED else PaywallReason.SUBSCRIPTION_EXPIRED
        log.info("Paywall shown: clubId={} reason={} userId={}", club.id, reason, actorUserId)
        // В отдельной транзакции: 402 ниже откатит текущую вместе со вставкой события.
        funnelEventRepository.recordDetached(FunnelStep.PAYWALL_SEEN, actorUserId, club.id)
        throw PaymentRequiredException(reason, club.id, subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT))
    }

    /** Отмена встречи до старта возвращает бесплатную чату (R5); для остальных встреч — no-op. */
    fun releaseFreeMeeting(eventId: UUID) {
        if (freeMeetingRepository.release(eventId) > 0) {
            log.info("Free meeting released by cancellation: eventId={}", eventId)
        }
    }
}
