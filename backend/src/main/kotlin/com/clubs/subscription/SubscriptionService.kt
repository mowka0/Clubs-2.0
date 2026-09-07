package com.clubs.subscription

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.CreateSubscriptionCommand
import com.clubs.payment.PaymentProvider
import com.clubs.payment.WebhookResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class SubscriptionService(
    private val repository: SubscriptionRepository,
    private val paymentProvider: PaymentProvider,
    private val mapper: SubscriptionMapper,
    @Value("\${features.member-pays-enabled:false}") private val memberPaysEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(SubscriptionService::class.java)

    @Transactional(readOnly = true)
    fun status(userId: UUID): SubscriptionStatusDto {
        val subscription = repository.findActiveOrganizerSubscription(userId)
        if (subscription != null) {
            return mapper.toStatusDto(subscription, repository.currentPriceKopecks(subscription.plan))
        }
        // Нет строки = неявный тариф FREE.
        return SubscriptionStatusDto(
            plan = SubscriptionPlan.FREE.literal,
            status = null,
            currentPeriodEnd = null,
            priceKopecks = repository.currentPriceKopecks(SubscriptionPlan.FREE),
        )
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

        // Тариф организатора действует на всю платформу: существующий активный тариф — это замена
        // тарифа, а не новая строка (partial-unique индекс запрещает две). Проратация отложена
        // (payment-v2.md §3.5).
        if (role == SubscriptionPayerRole.ORGANIZER) {
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
        val created = try {
            repository.create(
                payerUserId = userId,
                payerRole = role,
                plan = plan,
                subjectClubId = request.subjectClubId,
                currentPeriodEnd = providerSub.currentPeriodEnd,
                providerToken = providerSub.providerToken,
            )
        } catch (e: DataIntegrityViolationException) {
            // Конкурентный двойной сабмит: partial-unique индекс уже сохранил консистентность БД;
            // отдаём чистый 409 вместо 500. (payment-v2.md §5.1, уникальность активной подписки.)
            throw ConflictException("Подписка уже оформляется — обновите страницу.")
        }
        log.info("Subscription created: id={} userId={} role={} plan={}", created.id, userId, role, plan)
        return mapper.toStatusDto(created, price)
    }

    @Transactional
    fun cancel(userId: UUID): SubscriptionStatusDto {
        val subscription = repository.findActiveOrganizerSubscription(userId)
            ?: throw NotFoundException("No active subscription to cancel")
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
     * Входящий вебхук провайдера. Проверка и парсинг живут в [PaymentProvider]; здесь мы мапим
     * результат в однонаправленный переход состояния, дедуплицированный по provider_event_id
     * (UNIQUE) для идемпотентности.
     */
    @Transactional
    fun handleWebhook(rawBody: String, signature: String?) {
        when (val result = paymentProvider.parseWebhook(rawBody, signature)) {
            is WebhookResult.RenewalSucceeded -> {
                val sub = repository.findByProviderToken(result.providerToken)
                if (sub == null) {
                    log.warn("Webhook renewal for unknown provider token (event {})", result.providerEventId)
                    return
                }
                if (!repository.recordEventIfNew(sub.id, result.providerEventId, "RENEWAL_SUCCEEDED")) return
                repository.extendPeriod(sub.id, result.newPeriodEnd)
                repository.transitionStatus(sub.id, listOf(SubscriptionStatus.PAST_DUE), SubscriptionStatus.ACTIVE)
                log.info("Subscription renewed: id={} newPeriodEnd={}", sub.id, result.newPeriodEnd)
            }
            is WebhookResult.RenewalFailed -> {
                val sub = repository.findByProviderToken(result.providerToken)
                if (sub == null) {
                    log.warn("Webhook renewal-failure for unknown provider token (event {})", result.providerEventId)
                    return
                }
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
