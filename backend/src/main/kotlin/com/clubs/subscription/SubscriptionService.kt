package com.clubs.subscription

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.PaymentRequiredException
import com.clubs.common.exception.ValidationException
import com.clubs.club.ClubRepository
import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.CreateSubscriptionCommand
import com.clubs.payment.PaymentProvider
import com.clubs.payment.WebhookResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val paymentProvider: PaymentProvider,
    private val mapper: SubscriptionMapper,
    private val clubRepository: ClubRepository,
    @Value("\${features.member-pays-enabled:false}") private val memberPaysEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(SubscriptionService::class.java)

    @Transactional(readOnly = true)
    fun status(userId: UUID): SubscriptionStatusDto {
        val subscription = repository.findActiveOrganizerSubscription(userId)
        if (subscription != null) {
            return mapper.toStatusDto(subscription, repository.currentPriceKopecks(subscription.plan))
        }
        // No row = implicit FREE plan.
        return SubscriptionStatusDto(
            plan = SubscriptionPlan.FREE.literal,
            status = null,
            currentPeriodEnd = null,
            maxPaidClubs = SubscriptionPlanPolicy.displayMaxPaidClubs(SubscriptionPlan.FREE),
            priceKopecks = repository.currentPriceKopecks(SubscriptionPlan.FREE),
        )
    }

    @Transactional(readOnly = true)
    fun listPlans(): List<PlanOptionDto> =
        SubscriptionPlan.values().map {
            PlanOptionDto(it.literal, SubscriptionPlanPolicy.displayMaxPaidClubs(it), repository.currentPriceKopecks(it))
        }

    /**
     * Capacity gate for creating a paid club. The caller (ClubService) supplies the live paid-club
     * count it already owns; throws 402 with the upgrade target when the plan ceiling is reached.
     */
    @Transactional(readOnly = true)
    fun requirePaidClubCapacity(userId: UUID, currentPaidClubCount: Int) {
        val plan = repository.findActiveOrganizerSubscription(userId)?.plan ?: SubscriptionPlan.FREE
        if (currentPaidClubCount >= SubscriptionPlanPolicy.maxPaidClubs(plan)) {
            val required = SubscriptionPlanPolicy.smallestPlanFor(currentPaidClubCount + 1)
            throw PaymentRequiredException(
                currentPlan = plan.literal,
                requiredPlan = required.literal,
                priceKopecks = repository.currentPriceKopecks(required),
            )
        }
    }

    @Transactional
    fun subscribe(userId: UUID, request: CreateSubscriptionRequest): SubscriptionStatusDto {
        val role = parseRole(request.role)
        val plan = parsePlan(request.plan)
        if (plan == SubscriptionPlan.FREE) throw ValidationException("Cannot subscribe to the FREE plan")

        if (role == SubscriptionPayerRole.MEMBER) {
            if (!memberPaysEnabled) throw ForbiddenException("Member subscriptions are not enabled")
            if (request.subjectClubId == null) throw ValidationException("subjectClubId is required for member subscriptions")
        }

        // Organizer plan is platform-wide: an existing live one is a plan-swap, not a new row
        // (the partial-unique index forbids two). Proration is deferred (payment-v2.md §3.5).
        if (role == SubscriptionPayerRole.ORGANIZER) {
            // Block a downgrade that would leave the organizer over the target plan's capacity
            // (payment-v2.md §4.3, decision A). They must free/delete clubs first.
            val paidClubs = clubRepository.countPaidByOwnerId(userId)
            if (SubscriptionPlanPolicy.maxPaidClubs(plan) < paidClubs) {
                throw ConflictException(
                    "На этом тарифе помещается меньше клубов, чем у вас сейчас платных ($paidClubs). " +
                        "Сделайте лишние клубы бесплатными или удалите их, затем меняйте тариф.",
                )
            }
            val existing = repository.findActiveOrganizerSubscription(userId)
            if (existing != null) {
                repository.updatePlan(existing.id, plan)
                log.info("Subscription plan swapped: userId={} {} -> {}", userId, existing.plan, plan)
                return status(userId)
            }
        }

        val price = repository.currentPriceKopecks(plan)
        val providerSub = paymentProvider.createSubscription(
            CreateSubscriptionCommand(userId, role, plan, price, request.subjectClubId),
        )
        val created = repository.create(
            payerUserId = userId,
            payerRole = role,
            plan = plan,
            subjectClubId = request.subjectClubId,
            currentPeriodEnd = providerSub.currentPeriodEnd,
            providerToken = providerSub.providerToken,
        )
        log.info("Subscription created: id={} userId={} role={} plan={}", created.id, userId, role, plan)
        return mapper.toStatusDto(created, price)
    }

    @Transactional
    fun cancel(userId: UUID): SubscriptionStatusDto {
        val subscription = repository.findActiveOrganizerSubscription(userId)
            ?: throw NotFoundException("No active subscription to cancel")
        // Cancelling reverts to FREE at period end. Block while over FREE capacity so the organizer
        // can never end up running more paid clubs than their plan allows (payment-v2.md §4.3, decision A).
        val paidClubs = clubRepository.countPaidByOwnerId(userId)
        val freeCapacity = SubscriptionPlanPolicy.maxPaidClubs(SubscriptionPlan.FREE)
        if (paidClubs > freeCapacity) {
            throw ConflictException(
                "Сейчас у вас $paidClubs платных клубов, а на бесплатном плане можно $freeCapacity. " +
                    "Сделайте лишние клубы бесплатными или удалите их, затем отменяйте подписку.",
            )
        }
        paymentProvider.cancelSubscription(subscription.providerToken)
        val rows = repository.transitionStatus(
            subscription.id,
            from = listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE),
            to = SubscriptionStatus.CANCELLED_PENDING_END,
        )
        if (rows == 0) throw ConflictException("Subscription state changed concurrently")
        log.info(
            "Subscription cancelled (pending end): id={} userId={} periodEnd={}",
            subscription.id, userId, subscription.currentPeriodEnd,
        )
        return status(userId)
    }

    /**
     * Inbound provider webhook. Verification + parsing live in [PaymentProvider]; here we map the
     * result to a forward-only transition, deduped by provider_event_id (UNIQUE) for idempotency.
     */
    @Transactional
    fun handleWebhook(rawBody: String, signature: String?) {
        when (val result = paymentProvider.parseWebhook(rawBody, signature)) {
            is WebhookResult.RenewalSucceeded -> {
                val sub = repository.findByProviderToken(result.providerToken) ?: return
                if (!repository.recordEventIfNew(sub.id, result.providerEventId, "RENEWAL_SUCCEEDED")) return
                repository.extendPeriod(sub.id, result.newPeriodEnd)
                repository.transitionStatus(sub.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE)
                log.info("Subscription renewed: id={} newPeriodEnd={}", sub.id, result.newPeriodEnd)
            }
            is WebhookResult.RenewalFailed -> {
                val sub = repository.findByProviderToken(result.providerToken) ?: return
                if (!repository.recordEventIfNew(sub.id, result.providerEventId, "RENEWAL_FAILED")) return
                repository.transitionStatus(sub.id, listOf(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE)
                log.warn("Subscription renewal failed → PAST_DUE: id={}", sub.id)
            }
            is WebhookResult.Ignored -> log.info("Subscription webhook ignored: {}", result.reason)
        }
    }

    private fun parsePlan(raw: String): SubscriptionPlan =
        try {
            SubscriptionPlan.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid plan: $raw")
        }

    private fun parseRole(raw: String): SubscriptionPayerRole =
        try {
            SubscriptionPayerRole.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid role: $raw")
        }
}
