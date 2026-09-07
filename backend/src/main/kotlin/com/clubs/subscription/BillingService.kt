package com.clubs.subscription

import com.clubs.chatlink.ChatLink
import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubCapability
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.CheckoutRequest
import com.clubs.payment.PaymentProvider
import com.clubs.payment.ResultNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/** Итог обработки ResultURL — контроллер по нему выбирает ответ провайдеру. */
enum class ResultOutcome {
    /** Оплата учтена. */
    APPLIED,
    /** Счёт уже был подтверждён или отклонён — повтор вебхука. */
    ALREADY_SETTLED,
    /** Счёт не найден — отвечаем OK, чтобы провайдер не ретраил. */
    UNKNOWN_INVOICE,
    /** Сумма не совпала с выставленной — состояние не меняем. */
    AMOUNT_MISMATCH,
}

/**
 * Биллинг платформы за чат (platform-billing.md § 6.3): статус для полоски и шита, чекаут
 * материнского платежа, подтверждение оплаты (ResultURL или опрос), ползунок автопродления.
 * Стена на создании встречи — в [BillingGate]; календарь продлений — в [BillingLifecycleService].
 */
@Service
class BillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentRepository: PlatformPaymentRepository,
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val clubRoleGuard: ClubRoleGuard,
    private val freeMeetingRepository: FreeMeetingRepository,
    private val funnelEventRepository: FunnelEventRepository,
    private val paymentProvider: PaymentProvider,
    private val notifier: BillingNotifier,
    // Грейс после конца оплаченного периода (R10).
    @Value("\${billing.grace-days:7}") private val graceDays: Long,
    // Оплаченный период за один платёж — часы идут с оплаты (R8).
    @Value("\${subscription.period-days:30}") private val periodDays: Long,
    // Повторный чекаут при живом неоплаченном счёте моложе этого окна отдаёт ту же ссылку.
    @Value("\${billing.checkout-reuse-minutes:30}") private val checkoutReuseMinutes: Long,
    @Value("\${billing.success-url}") private val successUrl: String,
    @Value("\${billing.fail-url}") private val failUrl: String,
    // Страницы возврата живут вне Telegram и без API: бот для кнопки «назад в Clubs» — в query.
    @Value("\${telegram.bot-username}") private val botUsername: String,
    // ФИО самозанятого-получателя целиком — в шите и оферте (PO 2026-09-07); только из env.
    @Value("\${billing.recipient-name:}") private val recipientName: String,
) {

    private val log = LoggerFactory.getLogger(BillingService::class.java)

    @Transactional(readOnly = true)
    fun status(clubId: UUID, userId: UUID): BillingStatusDto {
        val club = clubRoleGuard.requireCapability(clubId, userId, ClubCapability.MANAGE_EVENTS)
        return buildStatus(club, OffsetDateTime.now())
    }

    @Transactional
    fun checkout(clubId: UUID, userId: UUID, autopay: Boolean): CheckoutDto {
        val club = requireOwner(clubId, userId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw ConflictException("Подписка нужна только клубу с чатом — сначала подключите чат")
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        val now = OffsetDateTime.now()

        val payment = paymentRepository.findPendingMother(clubId, now.minusMinutes(checkoutReuseMinutes))
            // Тот же счёт, но ползунок мог переключиться между попытками: решение владельца
            // всегда берётся из последнего чекаута (ревью 2026-09-07).
            ?.also { paymentRepository.updateAutopayRequested(it.id, autopay) }
            ?.copy(autopayRequested = autopay)
            ?: paymentRepository.create(
                clubId = clubId,
                subscriptionId = liveSubscription(clubId)?.id,
                kind = PaymentKind.MOTHER,
                amountKopecks = price,
                previousInvId = null,
                autopayRequested = autopay,
            ).also {
                funnelEventRepository.record(FunnelStep.CHECKOUT_STARTED, userId, clubId)
                log.info("Billing checkout: clubId={} invId={} amountKopecks={} autopay={}", clubId, it.invId, price, autopay)
            }

        // Recurring всегда: карта сохраняется у провайдера, и ползунок можно включить позже
        // без новой оплаты. Списывать или нет — решает ползунок, не флаг чекаута.
        val url = paymentProvider.createCheckout(
            CheckoutRequest(
                invId = payment.invId,
                amountKopecks = payment.amountKopecks,
                description = describe(club, link),
                recurring = true,
                clubId = clubId,
                successUrl = "$successUrl?club=$clubId&bot=$botUsername",
                failUrl = "$failUrl?club=$clubId&bot=$botUsername",
            ),
        )
        return CheckoutDto(url.value, payment.invId)
    }

    /**
     * Подтверждение оплаты — из ResultURL или из опроса состояния. Идемпотентность — атомарный
     * `PENDING → SUCCEEDED` на счёте: повтор вебхука упирается в 0 строк и ничего не меняет.
     * Строка подписки рождается здесь же, с первым успешным материнским платежом.
     */
    @Transactional
    fun onResult(notification: ResultNotification): ResultOutcome {
        val payment = paymentRepository.findByInvId(notification.invId)
        if (payment == null) {
            log.warn("Billing result for unknown invoice: invId={}", notification.invId)
            return ResultOutcome.UNKNOWN_INVOICE
        }
        if (notification.amountKopecks != payment.amountKopecks) {
            log.warn(
                "Billing result amount mismatch: invId={} expected={} got={}",
                notification.invId, payment.amountKopecks, notification.amountKopecks,
            )
            return ResultOutcome.AMOUNT_MISMATCH
        }
        val now = OffsetDateTime.now()
        // Клуб ищем ДО отметки об оплате: иначе у удалённого клуба счёт оставался бы SUCCEEDED без
        // подписки и без единого громкого сигнала (ревью 2026-09-07).
        val club = clubRepository.findById(payment.clubId)
        if (club == null) {
            log.error(
                "Оплата за удалённый клуб — нужен возврат вручную: invId={} clubId={} amountKopecks={}",
                notification.invId, payment.clubId, payment.amountKopecks,
            )
            paymentRepository.markSucceeded(payment.id, notification.paymentMethod, notification.fee, now)
            return ResultOutcome.UNKNOWN_INVOICE
        }
        if (paymentRepository.markSucceeded(payment.id, notification.paymentMethod, notification.fee, now) == 0) {
            return ResultOutcome.ALREADY_SETTLED
        }

        val subscription = when (payment.kind) {
            PaymentKind.MOTHER -> settleMother(payment, club, notification, now)
            PaymentKind.RECURRING -> settleRecurring(payment, club, now) ?: return ResultOutcome.UNKNOWN_INVOICE
        }
        paymentRepository.attachSubscription(payment.id, subscription.id)
        subscriptionRepository.recordEventIfNew(subscription.id, "${paymentProvider.id}:paid:${payment.invId}", "PAID_${payment.kind}")
        funnelEventRepository.record(FunnelStep.PAYMENT_SUCCEEDED, club.ownerId, club.id)
        log.info(
            "Billing payment applied: invId={} kind={} clubId={} subscriptionId={} method={}",
            payment.invId, payment.kind, club.id, subscription.id, notification.paymentMethod,
        )
        return ResultOutcome.APPLIED
    }

    /** Сервисный вход стаб-провайдера (`/api/billing/stub/pay`): счёт «оплачивается» переходом по ссылке. */
    @Transactional
    fun settleStubPayment(invId: Long, paymentMethod: String): UUID? {
        check(paymentProvider.id == "stub") { "Stub settlement is available only with the stub provider" }
        val payment = paymentRepository.findByInvId(invId) ?: return null
        onResult(ResultNotification(invId, payment.amountKopecks, paymentMethod, fee = null))
        return payment.clubId
    }

    @Transactional
    fun setAutopay(clubId: UUID, userId: UUID, autopay: Boolean): BillingStatusDto {
        val club = requireOwner(clubId, userId)
        val subscription = liveSubscription(clubId)
            ?: throw ConflictException("Подписки ещё нет — оплатите первый месяц, ползунок появится")
        if (autopay && !subscription.autopayPossible) {
            throw ConflictException("Автопродление работает только для карт — оплатите следующий месяц картой")
        }
        subscriptionRepository.updateAutopay(subscription.id, autopay)
        log.info("Billing autopay set: clubId={} subscriptionId={} autopay={}", clubId, subscription.id, autopay)
        return buildStatus(club, OffsetDateTime.now())
    }

    private fun settleMother(payment: PlatformPayment, club: Club, notification: ResultNotification, now: OffsetDateTime): ServiceSubscription {
        val autopayPossible = isCard(notification.paymentMethod)
        val live = payment.subscriptionId?.let(subscriptionRepository::findById)?.takeIf { it.status != SubscriptionStatus.ENDED }
            ?: liveSubscription(club.id)
        val subscription = if (live == null) {
            subscriptionRepository.createChatSubscription(
                payerUserId = club.ownerId,
                clubId = club.id,
                currentPeriodEnd = now.plusDays(periodDays),
                providerToken = payment.invId.toString(),
                autopay = payment.autopayRequested,
                autopayPossible = autopayPossible,
            )
        } else {
            val newEnd = maxOf(now, live.currentPeriodEnd).plusDays(periodDays)
            subscriptionRepository.extendPeriod(live.id, newEnd)
            subscriptionRepository.transitionStatus(live.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE)
            subscriptionRepository.markMotherPaid(live.id, payment.invId.toString(), payment.autopayRequested, autopayPossible)
            live.copy(currentPeriodEnd = newEnd, status = SubscriptionStatus.ACTIVE)
        }
        notifier.paid(club, subscription.currentPeriodEnd, autopayOn = payment.autopayRequested && autopayPossible, priceKopecks = payment.amountKopecks)
        return subscription
    }

    private fun settleRecurring(payment: PlatformPayment, club: Club, now: OffsetDateTime): ServiceSubscription? {
        val subscription = payment.subscriptionId?.let(subscriptionRepository::findById)
        if (subscription == null) {
            log.warn("Recurring payment without subscription: invId={}", payment.invId)
            return null
        }
        val newEnd = maxOf(now, subscription.currentPeriodEnd).plusDays(periodDays)
        subscriptionRepository.extendPeriod(subscription.id, newEnd)
        // ENDED тоже оживает: списание могло подтвердиться уже после конца грейса, и без этого
        // перехода владелец платил бы, читал «Продлено» и продолжал получать 402 (ревью 2026-09-07).
        subscriptionRepository.transitionStatus(
            subscription.id,
            listOf(SubscriptionStatus.PAST_DUE, SubscriptionStatus.ENDED),
            SubscriptionStatus.ACTIVE,
        )
        subscriptionRepository.resetChargeAttempts(subscription.id)
        notifier.renewed(club, newEnd)
        return subscription.copy(currentPeriodEnd = newEnd, status = SubscriptionStatus.ACTIVE)
    }

    private fun buildStatus(club: Club, now: OffsetDateTime): BillingStatusDto {
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        val link = chatLinkRepository.findByClubId(club.id)
            ?: return mapper().toStatusDto(BillingState.NO_CHAT, price, null, null, pendingCheckout = false, recipientName = recipientName)
        val pending = paymentRepository.hasPendingMother(club.id)
        val subscription = subscriptionRepository.findLatestByClub(club.id)
        val state = when {
            subscription == null ->
                if (freeMeetingRepository.isUsed(link.chatId)) BillingState.FREE_MEETING_USED else BillingState.FREE_MEETING_AVAILABLE
            !subscription.allowsNewMeetings(now, graceDays) -> BillingState.ENDED
            now.isBefore(subscription.currentPeriodEnd) -> BillingState.ACTIVE
            else -> BillingState.GRACE
        }
        val graceUntil = subscription?.currentPeriodEnd?.plusDays(graceDays)?.takeIf { state == BillingState.GRACE || state == BillingState.ENDED }
        return mapper().toStatusDto(state, price, subscription, graceUntil, pending, recipientName)
    }

    private fun requireOwner(clubId: UUID, userId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Оплачивать подписку за чат может только владелец клуба")
        return club
    }

    private fun liveSubscription(clubId: UUID): ServiceSubscription? =
        subscriptionRepository.findLatestByClub(clubId)?.takeIf { it.status != SubscriptionStatus.ENDED }

    // На странице оплаты провайдера человек читает «за клуб» (PO 2026-09-07), хотя единица счёта — чат.
    private fun describe(club: Club, link: ChatLink): String =
        "Clubs: подписка за клуб ${link.chatTitle ?: club.name} на $periodDays дней"

    private fun mapper() = SubscriptionMapper()

    companion object {
        /** Robokassa делает дочерние списания только по банковским картам. */
        fun isCard(paymentMethod: String?): Boolean = paymentMethod?.contains("card", ignoreCase = true) == true
    }
}
