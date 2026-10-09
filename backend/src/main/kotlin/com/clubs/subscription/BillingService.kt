package com.clubs.subscription

import com.clubs.chatlink.ChatLink
import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.membership.MembershipRepository
import com.clubs.payment.CheckoutRequest
import com.clubs.payment.PaymentProvider
import com.clubs.payment.ResultNotification
import com.clubs.reputation.ReputationService
import com.clubs.user.UserRepository
import com.clubs.user.displayName
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
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
// Проверка настройки провайдера — до сборки сервиса: иначе незаданный BILLING_PROVIDER падает
// невнятным «no qualifying bean of type PaymentProvider» (staging 2026-09-15).
@DependsOn("billingProviderCheck")
class BillingService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentRepository: PlatformPaymentRepository,
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository,
    private val reputationService: ReputationService,
    private val chatTrialRepository: ChatTrialRepository,
    private val funnelEventRepository: FunnelEventRepository,
    private val consentRepository: AutopayConsentRepository,
    private val paymentProvider: PaymentProvider,
    private val notifier: BillingNotifier,
    // Грейс после конца оплаченного периода (R10).
    @Value("\${billing.grace-days:7}") private val graceDays: Long,
    // Бесплатный период чата от первой созданной встречи (решение PO 2026-09-15).
    @Value("\${billing.trial-days:15}") private val trialDays: Long,
    // Оплаченный период за один платёж — часы идут с оплаты (R8).
    @Value("\${subscription.period-days:30}") private val periodDays: Long,
    // Повторный чекаут при живом неоплаченном счёте моложе этого окна отдаёт ту же ссылку.
    @Value("\${billing.checkout-reuse-minutes:30}") private val checkoutReuseMinutes: Long,
    @Value("\${billing.success-url}") private val successUrl: String,
    @Value("\${billing.fail-url}") private val failUrl: String,
    // ФИО самозанятого-получателя целиком — в шите и оферте (PO 2026-09-07); только из env.
    @Value("\${billing.recipient-name:}") private val recipientName: String,
) {

    private val log = LoggerFactory.getLogger(BillingService::class.java)

    /** Статус видит любой участник: плашка «пора платить» показывается всем (billing-member-pays.md M4). */
    @Transactional(readOnly = true)
    fun status(clubId: UUID, userId: UUID): BillingStatusDto =
        buildStatus(requireMember(clubId, userId), userId, OffsetDateTime.now())

    /**
     * Чекаут материнского платежа. Платит любой участник (M1), но автосписание — только с карты
     * владельца (M2): участнику сервер сам выключает и отметку согласия, и сохранение карты,
     * что бы ни пришло в запросе.
     */
    @Transactional
    fun checkout(clubId: UUID, userId: UUID, autopayRequested: Boolean): CheckoutDto {
        val club = requireMember(clubId, userId)
        val link = chatLinkRepository.findByClubId(clubId)
            ?: throw ConflictException("Подписка нужна только клубу с чатом — сначала подключите чат")
        val isOwner = club.ownerId == userId
        val autopay = autopayRequested && isOwner
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        val now = OffsetDateTime.now()

        val payment = paymentRepository.findPendingMother(clubId, userId, now.minusMinutes(checkoutReuseMinutes))
            // Тот же счёт, но ползунок мог переключиться между попытками: решение владельца
            // всегда берётся из последнего чекаута (ревью 2026-09-07).
            ?.also { paymentRepository.updateAutopayRequested(it.id, autopay) }
            ?.copy(autopayRequested = autopay)
            ?: paymentRepository.create(
                clubId = clubId,
                payerUserId = userId,
                subscriptionId = liveSubscription(clubId)?.id,
                kind = PaymentKind.MOTHER,
                amountKopecks = price,
                previousInvId = null,
                autopayRequested = autopay,
            ).also {
                funnelEventRepository.record(FunnelStep.CHECKOUT_STARTED, userId, clubId)
                log.info(
                    "Billing checkout: clubId={} invId={} amountKopecks={} autopay={} byOwner={}",
                    clubId, it.invId, price, autopay, isOwner,
                )
            }
        // История согласий (V102, требование Robokassa): строка на каждый чекаут — и с отметкой, и без.
        consentRepository.record(
            AutopayConsent(clubId, userId, ConsentSource.CHECKOUT, granted = autopay, paymentId = payment.id, subscriptionId = payment.subscriptionId),
        )

        // Recurring — у владельца всегда, когда провайдер его умеет: карта сохраняется, и ползунок
        // можно включить позже без новой оплаты. Списывать или нет — решает ползунок, не флаг чекаута.
        // Пока услуга магазину не разрешена (Robokassa, ошибка 34), платим без неё — иначе не проходит
        // ничего. Карту участника не сохраняем никогда: списывать с неё некому разрешить (M2).
        val url = paymentProvider.createCheckout(
            CheckoutRequest(
                invId = payment.invId,
                amountKopecks = payment.amountKopecks,
                description = describe(club, link),
                recurring = isOwner && paymentProvider.recurringAvailable,
                clubId = clubId,
                successUrl = "$successUrl?club=$clubId",
                failUrl = "$failUrl?club=$clubId",
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
        // Рекуррент магазину не разрешён: сохранённую карту шедулер всё равно не списывает (шлёт
        // напоминания), и включённый ползунок обещал бы автопродление, которого не будет.
        if (autopay && !paymentProvider.recurringAvailable) {
            throw ConflictException("Автопродление сейчас недоступно — напомним о продлении в личке")
        }
        if (autopay && !subscription.autopayPossible) {
            throw ConflictException("Автопродление недоступно: карта для списания не сохранена — оплатите следующий месяц картой")
        }
        subscriptionRepository.updateAutopay(subscription.id, autopay)
        // Включил = согласие, выключил = отзыв — обе записи нужны для разбора спора о списании.
        consentRepository.record(AutopayConsent(clubId, userId, ConsentSource.TOGGLE, granted = autopay, subscriptionId = subscription.id))
        log.info("Billing autopay set: clubId={} subscriptionId={} autopay={}", clubId, subscription.id, autopay)
        return buildStatus(club, userId, OffsetDateTime.now())
    }

    private fun settleMother(payment: PlatformPayment, club: Club, notification: ResultNotification, now: OffsetDateTime): ServiceSubscription {
        val live = payment.subscriptionId?.let(subscriptionRepository::findById)?.takeIf { it.status != SubscriptionStatus.ENDED }
            ?: liveSubscription(club.id)
        if (payment.payerUserId != club.ownerId) return settleMemberPayment(payment, club, live, now)
        // Карта сохранена только если мы просили Recurring (провайдер его умеет) и платили картой:
        // иначе шедулер пошёл бы списывать по несуществующему токену.
        val autopayPossible = paymentProvider.recurringAvailable && isCard(notification.paymentMethod)
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

    /**
     * Участник оплатил месяц (billing-member-pays.md M3): период сдвигается на 30 дней, а карта,
     * токен и ползунок владельца остаются — его автосписание уйдёт в новый день окончания. Подписка,
     * рождённая оплатой участника, остаётся подпиской владельца, но без карты: автопродление
     * появится, когда владелец сам оплатит месяц картой.
     */
    private fun settleMemberPayment(payment: PlatformPayment, club: Club, live: ServiceSubscription?, now: OffsetDateTime): ServiceSubscription {
        val subscription = if (live == null) {
            subscriptionRepository.createChatSubscription(
                payerUserId = club.ownerId,
                clubId = club.id,
                currentPeriodEnd = now.plusDays(periodDays),
                providerToken = null,
                autopay = false,
                autopayPossible = false,
            )
        } else {
            val newEnd = maxOf(now, live.currentPeriodEnd).plusDays(periodDays)
            subscriptionRepository.extendPeriod(live.id, newEnd)
            subscriptionRepository.transitionStatus(live.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE)
            // Новый цикл продления: неудачные попытки списать с карты владельца остались в прошлом.
            subscriptionRepository.resetChargeAttempts(live.id)
            live.copy(currentPeriodEnd = newEnd, status = SubscriptionStatus.ACTIVE)
        }
        // Надёжность за оплату — не чаще раза в оплаченный период (M7): предоплата на год вперёд
        // двенадцатью счетами иначе покупала бы её.
        reputationService.rewardClubBillingPayment(
            userId = payment.payerUserId, clubId = club.id, paymentId = payment.id,
            paidAt = now, notBefore = now.minusDays(periodDays),
        )
        notifier.paidByMember(
            club, payment.payerUserId, subscription.currentPeriodEnd,
            ownerAutopayOn = live?.renewsAutomatically(paymentProvider.recurringAvailable) == true,
        )
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

    private fun buildStatus(club: Club, userId: UUID, now: OffsetDateTime): BillingStatusDto {
        // Отметка согласия и ползунок — только владельцу: участник платит разово (M2).
        val canEnableAutopay = club.ownerId == userId
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        // Рекуррент магазину не разрешён (ROBOKASSA_RECURRING_ENABLED=false) — фронт прячет обещание списания.
        val autopayAvailable = paymentProvider.recurringAvailable
        val link = chatLinkRepository.findByClubId(club.id)
            ?: return mapper().toStatusDto(
                BillingState.NO_CHAT, price, trialUntil = null, trialDays = trialDays.toInt(),
                subscription = null, graceUntil = null, pendingCheckout = false,
                recipientName = recipientName, canEnableAutopay = canEnableAutopay, autopayAvailable = autopayAvailable,
                paymentDue = false, lastPayer = null,
            )
        // «Проверяем оплату» — только по своим счетам: чужой брошенный чекаут тебя не касается.
        val pending = paymentRepository.hasPendingMother(club.id, userId)
        val subscription = subscriptionRepository.findLatestByClub(club.id)
        // Бесплатный период идёт по чату и от первой встречи: до неё строки нет вовсе.
        val trialUntil = chatTrialRepository.findStartedAt(link.chatId)?.plusDays(trialDays)
        val state = when {
            // Бота выгнали: подписка на паузе, даты сохраняем — владелец видит, что ничего не пропало.
            !link.botStatus.isInChat -> BillingState.BOT_REMOVED
            subscription == null -> when {
                trialUntil == null -> BillingState.TRIAL_NOT_STARTED
                now.isBefore(trialUntil) -> BillingState.TRIAL
                else -> BillingState.TRIAL_ENDED
            }
            !subscription.allowsNewMeetings(now, graceDays) -> BillingState.ENDED
            // Списание не прошло (бывает и до конца периода: слот 0 — утро дня окончания) — владельцу
            // уже пришло «не удалось списать», и полоска зовёт «Продлить», а не обещает карту.
            subscription.status == SubscriptionStatus.PAST_DUE -> BillingState.GRACE
            now.isBefore(subscription.currentPeriodEnd) -> BillingState.ACTIVE
            subscription.awaitsAutoRenewal(
                paymentProvider.recurringAvailable,
                chargeInFlight = paymentRepository.hasPendingRecurring(subscription.id),
            ) -> BillingState.ACTIVE
            else -> BillingState.GRACE
        }
        val graceUntil = subscription?.currentPeriodEnd?.plusDays(graceDays)?.takeIf { state == BillingState.GRACE || state == BillingState.ENDED }
        val lastPayer = if (state == BillingState.ACTIVE || state == BillingState.GRACE) lastPayer(club.id) else null
        return mapper().toStatusDto(
            state, price, trialUntil?.takeIf { state == BillingState.TRIAL }, trialDays.toInt(),
            subscription, graceUntil, pending, recipientName, canEnableAutopay, autopayAvailable,
            paymentDue = isPaymentDue(state, trialUntil, subscription?.currentPeriodEnd, now),
            lastPayer = lastPayer,
        )
    }

    /**
     * Пора платить — плашка возвращается на главную ко всем участникам (M4). Считается от даты, а
     * не от тика шедулера: пропущенный тик плашку не задержит.
     */
    private fun isPaymentDue(state: BillingState, trialUntil: OffsetDateTime?, periodEnd: OffsetDateTime?, now: OffsetDateTime): Boolean =
        when (state) {
            BillingState.TRIAL -> trialUntil != null && BillingLifecycleService.calendarDaysUntil(trialUntil, now) <= PAYMENT_DUE_DAYS
            BillingState.ACTIVE -> periodEnd != null && BillingLifecycleService.calendarDaysUntil(periodEnd, now) <= PAYMENT_DUE_DAYS
            BillingState.TRIAL_ENDED, BillingState.GRACE, BillingState.ENDED -> true
            BillingState.NO_CHAT, BillingState.BOT_REMOVED, BillingState.TRIAL_NOT_STARTED -> false
        }

    private fun lastPayer(clubId: UUID): BillingPayerDto? {
        val payerId = paymentRepository.findLastSucceeded(clubId)?.payerUserId ?: return null
        val user = userRepository.findById(payerId) ?: return null
        return BillingPayerDto(payerId, displayName(user.firstName, user.lastName))
    }

    // Платит любой участник (M1); владелец — тоже участник, у него строка членства «organizer».
    private fun requireMember(clubId: UUID, userId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (!membershipRepository.isActiveMemberInActiveClub(userId, clubId)) {
            throw ForbiddenException("Оплачивать подписку за клуб могут только его участники")
        }
        return club
    }

    // Ползунок автопродления — только у владельца: карта для списаний — его (M2).
    private fun requireOwner(clubId: UUID, userId: UUID): Club {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Автопродлением управляет только владелец клуба")
        return club
    }

    private fun liveSubscription(clubId: UUID): ServiceSubscription? =
        subscriptionRepository.findLatestByClub(clubId)?.takeIf { it.status != SubscriptionStatus.ENDED }

    // На странице оплаты провайдера человек читает «за клуб» (PO 2026-09-07), хотя единица счёта — чат.
    // Название обрезается: оно дважды попадает в GET-ссылку (Description и Receipt, кириллица до
    // 10 байт на знак), и длинное имя чата вывело бы ссылку за лимит сервера Robokassa.
    private fun describe(club: Club, link: ChatLink): String =
        "Clubs: подписка за клуб ${(link.chatTitle ?: club.name).take(TITLE_IN_DESCRIPTION_MAX)} на $periodDays дней"

    private fun mapper() = SubscriptionMapper()

    companion object {
        /** Robokassa делает дочерние списания только по банковским картам. */
        fun isCard(paymentMethod: String?): Boolean = paymentMethod?.contains("card", ignoreCase = true) == true

        // Сколько знаков названия чата входит в описание платежа (см. describe); хвост « на 30 дней» при этом сохраняется.
        private const val TITLE_IN_DESCRIPTION_MAX = 40

        // За сколько календарных дней МСК до конца периода плашка возвращается на главную (M4) — та же
        // неделя, что у первого напоминания о конце бесплатного периода.
        private const val PAYMENT_DUE_DAYS = 7L
    }
}
