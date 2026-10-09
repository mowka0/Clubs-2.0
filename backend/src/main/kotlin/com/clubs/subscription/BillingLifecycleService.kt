package com.clubs.subscription

import com.clubs.chatlink.ChatLinkRepository
import com.clubs.club.Club
import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.PaymentProvider
import com.clubs.payment.PaymentState
import com.clubs.payment.RecurringChargeRequest
import com.clubs.payment.ResultNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Календарь подписки за чат (platform-billing.md § 6.5). Ежедневно — напоминания, дочерние
 * списания по слотам [retryDays], PAST_DUE по концу периода, ENDED по концу грейса. Ежечасно —
 * опрос провайдера по счетам без ответа. Клуб без чата доживает период тихо: ни списаний, ни DM.
 * «За N дней» и слоты — календарные дни МСК, а не 24-часовые отрезки: иначе «завтра» приходило
 * в сам день окончания, а списание — на день позже обещанного офертой (bugfix 2026-10-07).
 */
@Service
class BillingLifecycleService(
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentRepository: PlatformPaymentRepository,
    private val chatLinkRepository: ChatLinkRepository,
    private val clubRepository: ClubRepository,
    private val funnelEventRepository: FunnelEventRepository,
    private val chatTrialRepository: ChatTrialRepository,
    private val paymentProvider: PaymentProvider,
    private val billingService: BillingService,
    private val notifier: BillingNotifier,
    @Value("\${billing.grace-days:7}") private val graceDays: Long,
    // Бесплатный период чата от первой созданной встречи (решение PO 2026-09-15).
    @Value("\${billing.trial-days:15}") private val trialDays: Long,
    @Value("\${subscription.period-days:30}") private val periodDays: Long,
    // Слоты дочерних списаний в днях от конца периода; четвёртой попытки нет — грейс кончился.
    @Value("\${billing.retry-days:0,1,3}") private val retryDays: List<Long>,
    // Через сколько часов без ответа счёт опрашивается у провайдера.
    @Value("\${billing.pending-timeout-hours:6}") private val pendingTimeoutHours: Long,
    // Материнский счёт без оплаты дольше этого срока считается брошенным.
    @Value("\${billing.mother-expire-hours:24}") private val motherExpireHours: Long,
) {

    private val log = LoggerFactory.getLogger(BillingLifecycleService::class.java)

    /**
     * Общей транзакции у тика намеренно нет: внутри идут внешние списания (HTTP к провайдеру), и
     * одна транзакция на весь обход означала бы, что ошибка на последней подписке откатывает
     * записи об уже отправленных списаниях — следующий тик списал бы те же деньги повторно
     * (ревью 2026-09-07). Каждый шаг коммитится сам по себе, а сбой на одной подписке не
     * останавливает остальные.
     */
    fun runDaily(now: OffsetDateTime) {
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        for (subscription in subscriptionRepository.findLive()) {
            try {
                processSubscription(subscription, now, price)
            } catch (e: RuntimeException) {
                log.error("Billing daily tick failed for subscription {}: {}", subscription.id, e.message, e)
            }
        }
        remindEndingTrials(now, price)
    }

    /**
     * Конец бесплатного периода наступает по календарю и сам по себе ничего не присылает, поэтому
     * стена встретила бы владельца молча. Два DM: за неделю и за день (решение PO 2026-09-15).
     */
    private fun remindEndingTrials(now: OffsetDateTime, price: Int) {
        // +1 день к выборке: период, кончающийся через 7 календарных дней поздно вечером, по
        // отрезкам ещё дальше недели, а по календарю уже в пороге.
        for (trial in chatTrialRepository.findTrialsEndingBefore(now.plusDays(TRIAL_REMINDER_DAYS.first() + 1), trialDays)) {
            try {
                remindTrial(trial, now, price)
            } catch (e: RuntimeException) {
                log.error("Trial reminder failed for chat {}: {}", trial.chatId, e.message, e)
            }
        }
    }

    private fun remindTrial(trial: ChatTrial, now: OffsetDateTime, price: Int) {
        val trialEnd = trial.startedAt.plusDays(trialDays)
        // Период уже кончился — напоминать поздно: стену человек увидит на создании встречи.
        if (!now.isBefore(trialEnd)) return
        val threshold = reminderThreshold(calendarDaysUntil(trialEnd, now), TRIAL_REMINDER_DAYS) ?: return
        // Порог уже отправляли (или отправляли более поздний) — тик повторяется, DM нет.
        if (trial.reminderDaysLeft != null && trial.reminderDaysLeft <= threshold) return
        val club = clubRepository.findById(trial.clubId) ?: return
        chatTrialRepository.markReminded(trial.chatId, threshold)
        notifier.trialEndingSoon(club, trialEnd, price, threshold)
        log.info("Trial reminder sent: chatId={} clubId={} threshold={}", trial.chatId, trial.clubId, threshold)
    }

    private fun processSubscription(subscription: ServiceSubscription, now: OffsetDateTime, price: Int) {
        val clubId = subscription.subjectClubId ?: return
        val club = clubRepository.findById(clubId)
        // «Есть чат» = бот в нём присутствует: выгнанный бот равен отсутствию чата — период
        // доживает тихо, без списаний и DM (PO 2026-09-16).
        val hasChat = club != null && chatLinkRepository.findByClubId(clubId)?.botStatus?.isInChat == true
        val periodEnd = subscription.currentPeriodEnd
        val graceEnd = periodEnd.plusDays(graceDays)

        if (!now.isBefore(graceEnd)) {
            endSubscription(subscription, club, hasChat)
            return
        }
        if (!hasChat || club == null) return

        // Без рекуррента у провайдера (услугу отозвали или ещё не включили) сохранённая карта
        // бесполезна: не списываем, а напоминаем — иначе отказ провайдера уронил бы подписку в PAST_DUE.
        // С автосписанием напоминаний нет: о дате списания сказано в DM об оплате (PO 2026-09-07).
        if (subscription.renewsAutomatically(paymentProvider.recurringAvailable)) {
            chargeIfSlotDue(subscription, club, now, price)
        } else if (now.isBefore(periodEnd)) {
            remindBeforeEnd(subscription, club, now, price)
        } else if (subscription.status == SubscriptionStatus.ACTIVE) {
            subscriptionRepository.transitionStatus(subscription.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE)
            notifier.periodEnded(club, graceEnd)
            log.info("Subscription period ended without autopay → PAST_DUE: id={} clubId={}", subscription.id, clubId)
        }
    }

    /**
     * Счета без ответа провайдера: узнать судьбу, зависшие — закрыть. Общей транзакции нет по той
     * же причине, что и у [runDaily]: опрос состояния — сетевой вызов, и сбой на одном счёте не
     * должен откатывать уже применённые оплаты (ревью 2026-09-07). Каждую оплату применяет
     * `billingService.onResult` в своей транзакции.
     */
    fun reconcilePending(now: OffsetDateTime) {
        for (payment in paymentRepository.findPendingCreatedBefore(now.minusHours(pendingTimeoutHours))) {
            try {
                reconcileOne(payment, now)
            } catch (e: RuntimeException) {
                log.error("Billing reconcile failed for invId {}: {}", payment.invId, e.message, e)
            }
        }
    }

    private fun reconcileOne(payment: PlatformPayment, now: OffsetDateTime) {
        val state = paymentProvider.queryState(payment.invId)
        when (state.state) {
            PaymentState.SUCCEEDED ->
                billingService.onResult(ResultNotification(payment.invId, payment.amountKopecks, state.paymentMethod, fee = null))
            PaymentState.FAILED -> {
                paymentRepository.markFailed(payment.id)
                if (payment.kind == PaymentKind.RECURRING) onChargeFailed(payment.subscriptionId, payment.amountKopecks)
                log.info("Billing payment failed at provider: invId={} kind={}", payment.invId, payment.kind)
            }
            // Провайдер молчит слишком долго. Закрываем счёт ЛЮБОГО вида: зависший дочерний иначе
            // навсегда блокировал бы ретраи и владелец не узнал бы о неудачном списании
            // (ревью 2026-09-07). Поздняя оплата закрытый счёт всё равно оживит — см. markSucceeded.
            PaymentState.PENDING ->
                if (payment.createdAt.isBefore(now.minusHours(motherExpireHours))) {
                    paymentRepository.markFailed(payment.id)
                    if (payment.kind == PaymentKind.RECURRING) onChargeFailed(payment.subscriptionId, payment.amountKopecks)
                    log.info("Stale payment closed: invId={} kind={} clubId={}", payment.invId, payment.kind, payment.clubId)
                }
        }
    }

    private fun endSubscription(subscription: ServiceSubscription, club: Club?, hasChat: Boolean) {
        val rows = subscriptionRepository.transitionStatus(
            subscription.id, listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), SubscriptionStatus.ENDED,
        )
        if (rows == 0) return
        log.info("Subscription ended after grace: id={} clubId={} hasChat={}", subscription.id, subscription.subjectClubId, hasChat)
        if (club != null && hasChat) {
            funnelEventRepository.record(FunnelStep.SUBSCRIPTION_ENDED, club.ownerId, club.id)
            notifier.graceExhausted(club)
        }
    }

    private fun remindBeforeEnd(subscription: ServiceSubscription, club: Club, now: OffsetDateTime, price: Int) {
        val periodEnd = subscription.currentPeriodEnd
        val threshold = reminderThreshold(calendarDaysUntil(periodEnd, now), SUBSCRIPTION_REMINDER_DAYS) ?: return
        // Дедуп по ключу события: тик может повториться, DM — нет.
        if (subscriptionRepository.recordEventIfNew(subscription.id, "reminder:${periodEnd.toEpochSecond()}:$threshold", "REMINDER")) {
            notifier.expiringSoon(club, periodEnd, price, threshold)
        }
    }

    /**
     * Слот попытки — тик в день (дата конца + retryDays[attempt]) по МСК. Слот 0 — день окончания,
     * даже если час окончания ещё впереди (оферта п. 3.4): продление всё равно идёт от конца периода.
     */
    private fun chargeIfSlotDue(subscription: ServiceSubscription, club: Club, now: OffsetDateTime, price: Int) {
        val attempt = subscription.chargeAttempts
        if (attempt >= retryDays.size) return
        val daysSinceEnd = -calendarDaysUntil(subscription.currentPeriodEnd, now)
        if (daysSinceEnd < retryDays[attempt]) return
        if (paymentRepository.hasPendingRecurring(subscription.id)) return
        sendRecurringCharge(subscription, club, now, price)
    }

    /**
     * Служебное списание вне календаря (platform-billing.md § 11): у Robokassa нет тестового
     * режима для рекуррента, и первое боевое дочернее списание проверяется на проде на чате PO,
     * не дожидаясь конца 30-дневного периода. Деньги уходят раньше, но период всё равно
     * продлевается от его конца (settleRecurring), так что оплаченное время не теряется.
     * Доступ — [ManualChargeAccess] в контроллере. Возвращает InvId отправленного счёта.
     */
    fun chargeNow(clubId: UUID, now: OffsetDateTime): Long {
        if (!paymentProvider.recurringAvailable) {
            throw ConflictException("Рекуррент магазину не разрешён (ROBOKASSA_RECURRING_ENABLED=false) — списывать нечем")
        }
        val subscription = subscriptionRepository.findLatestByClub(clubId)?.takeIf { it.status != SubscriptionStatus.ENDED }
            ?: throw ConflictException("У клуба нет живой подписки — списывать нечего")
        if (!subscription.autopayPossible || subscription.providerToken == null) {
            throw ConflictException("Материнский платёж был не картой — сохранённого способа оплаты нет")
        }
        // Выключенный ползунок = отозванное согласие (autopay_consent): служебный триггер его тоже уважает.
        if (!subscription.autopay) {
            throw ConflictException("Автопродление выключено владельцем — списывать нельзя")
        }
        if (chatLinkRepository.findByClubId(clubId)?.botStatus?.isInChat != true) {
            throw ConflictException("Бота нет в чате клуба — списывать не за что")
        }
        if (paymentRepository.hasPendingRecurring(subscription.id)) {
            throw ConflictException("Предыдущее списание ещё не подтверждено провайдером")
        }
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val price = subscriptionRepository.currentPriceKopecks(SubscriptionPlan.CHAT)
        log.warn("MANUAL recurring charge triggered: clubId={} subscriptionId={}", clubId, subscription.id)
        return sendRecurringCharge(subscription, club, now, price)
    }

    private fun sendRecurringCharge(subscription: ServiceSubscription, club: Club, now: OffsetDateTime, price: Int): Long {
        val previousInvId = subscription.providerToken!!.toLong()
        val payment = paymentRepository.create(
            // Дочернее списание идёт с сохранённой карты владельца — участник её сохранить не может (M2).
            clubId = club.id, payerUserId = club.ownerId, subscriptionId = subscription.id, kind = PaymentKind.RECURRING,
            amountKopecks = price, previousInvId = previousInvId, autopayRequested = true,
        )
        subscriptionRepository.recordChargeAttempt(subscription.id, now)
        val accepted = paymentProvider.charge(
            RecurringChargeRequest(
                invId = payment.invId, previousInvId = previousInvId, amountKopecks = price,
                description = "Clubs: продление подписки за чат ${club.name} на $periodDays дней", clubId = club.id,
            ),
        )
        log.info(
            "Recurring charge sent: subscriptionId={} invId={} attempt={} accepted={}",
            subscription.id, payment.invId, subscription.chargeAttempts + 1, accepted.accepted,
        )
        if (!accepted.accepted) {
            paymentRepository.markFailed(payment.id)
            onChargeFailed(subscription.id, price)
        }
        return payment.invId
    }

    /** Первая неудача: ACTIVE → PAST_DUE и DM; дальнейшие — молча, ретраи по слотам. */
    private fun onChargeFailed(subscriptionId: UUID?, price: Int) {
        val subscription = subscriptionId?.let(subscriptionRepository::findById) ?: return
        val moved = subscriptionRepository.transitionStatus(subscription.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE)
        if (moved > 0) {
            val club = subscription.subjectClubId?.let(clubRepository::findById) ?: return
            notifier.chargeFailed(club, price, subscription.currentPeriodEnd.plusDays(graceDays))
        }
    }

    companion object {
        /** Пояс календаря подписки: тик, «за N дней» и слоты списаний считаются по Москве. */
        const val ZONE_ID = "Europe/Moscow"
        private val ZONE: ZoneId = ZoneId.of(ZONE_ID)

        // Пороги напоминаний о конце бесплатного периода, в календарных днях до конца (решение PO
        // 2026-09-15). Первый порог задаёт и окно выборки кандидатов, последний — «завтра».
        private val TRIAL_REMINDER_DAYS = listOf(7L, 1L)

        // Пороги напоминаний о конце оплаченного периода без автосписания: «за 3 дня» и «завтра».
        private val SUBSCRIPTION_REMINDER_DAYS = listOf(3L, 1L)

        /** Календарных дней МСК от сегодня до даты [end]: 0 — кончается сегодня, отрицательное — уже прошло. */
        fun calendarDaysUntil(end: OffsetDateTime, now: OffsetDateTime): Long =
            ChronoUnit.DAYS.between(now.atZoneSameInstant(ZONE).toLocalDate(), end.atZoneSameInstant(ZONE).toLocalDate())

        /**
         * Порог, в который попадает остаток: ближайший сверху из [thresholds]. null — до порогов далеко
         * или период кончается сегодня (сказать «завтра» уже нельзя). Пропущенный тик догоняет порог.
         */
        private fun reminderThreshold(daysLeft: Long, thresholds: List<Long>): Int? =
            if (daysLeft < 1) null else thresholds.filter { daysLeft <= it }.minOrNull()?.toInt()
    }
}
