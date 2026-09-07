package com.clubs.subscription

import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SubscriptionPayerRole
import com.clubs.generated.jooq.enums.SubscriptionPlan
import com.clubs.generated.jooq.enums.SubscriptionStatus
import com.clubs.payment.PaymentProvider
import com.clubs.payment.WebhookResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class SubscriptionServiceTest {

    private val repository = mockk<SubscriptionRepository>(relaxed = true)
    private val provider = mockk<PaymentProvider>(relaxed = true)
    private val mapper = SubscriptionMapper()
    private val userId: UUID = UUID.randomUUID()

    private fun service(memberPaysEnabled: Boolean = false) =
        SubscriptionService(repository, provider, mapper, memberPaysEnabled)

    private fun sub(
        plan: SubscriptionPlan,
        status: SubscriptionStatus,
        token: String? = "tok",
    ) = ServiceSubscription(
        id = UUID.randomUUID(),
        payerUserId = userId,
        payerRole = SubscriptionPayerRole.ORGANIZER,
        plan = plan,
        subjectClubId = null,
        status = status,
        currentPeriodEnd = OffsetDateTime.now().plusDays(10),
        providerToken = token,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

    @BeforeEach
    fun setUp() {
        every { repository.findActiveOrganizerSubscription(any()) } returns null
        every { repository.currentPriceKopecks(SubscriptionPlan.FREE) } returns 0
        every { repository.currentPriceKopecks(SubscriptionPlan.TRIO) } returns 20000
        every { repository.currentPriceKopecks(SubscriptionPlan.UNLIMITED) } returns 40000
    }

    @Test
    fun `cannot subscribe to the FREE plan`() {
        assertThrows<ValidationException> { service().subscribe(userId, CreateSubscriptionRequest(plan = "FREE")) }
    }

    @Test
    fun `member subscribe is blocked while the flag is off`() {
        assertThrows<ForbiddenException> {
            service(memberPaysEnabled = false).subscribe(
                userId,
                CreateSubscriptionRequest(plan = "TRIO", role = "MEMBER", subjectClubId = UUID.randomUUID()),
            )
        }
    }

    @Test
    fun `cancel without a subscription throws NotFound`() {
        assertThrows<NotFoundException> { service().cancel(userId) }
    }

    @Test
    fun `cancel moves ACTIVE to CANCELLED_PENDING_END and tells the provider`() {
        val existing = sub(SubscriptionPlan.TRIO, SubscriptionStatus.ACTIVE)
        every { repository.findActiveOrganizerSubscription(userId) } returns existing
        every { repository.transitionStatus(existing.id, any(), SubscriptionStatus.CANCELLED_PENDING_END) } returns 1

        service().cancel(userId)

        verify { provider.cancelSubscription(existing.providerToken) }
        verify {
            repository.transitionStatus(
                existing.id,
                match { it.contains(SubscriptionStatus.ACTIVE) },
                SubscriptionStatus.CANCELLED_PENDING_END,
            )
        }
    }

    @Test
    fun `organizer upgrade swaps the plan without a new charge`() {
        val existing = sub(SubscriptionPlan.TRIO, SubscriptionStatus.ACTIVE)
        every { repository.findActiveOrganizerSubscription(userId) } returns existing

        service().subscribe(userId, CreateSubscriptionRequest(plan = "UNLIMITED"))

        verify { repository.updatePlan(existing.id, SubscriptionPlan.UNLIMITED) }
        verify(exactly = 0) { provider.createSubscription(any()) }
    }

    @Test
    fun `webhook renewal is idempotent - a duplicate event extends nothing`() {
        val existing = sub(SubscriptionPlan.TRIO, SubscriptionStatus.ACTIVE, token = "tok")
        every { provider.parseWebhook(any(), any()) } returns
            WebhookResult.RenewalSucceeded("evt-1", "tok", existing.currentPeriodEnd.plusDays(30))
        every { repository.findByProviderToken("tok") } returns existing
        every { repository.recordEventIfNew(existing.id, "evt-1", any()) } returns false // событие уже видели

        service().handleWebhook("{}", null)

        verify(exactly = 0) { repository.extendPeriod(any(), any()) }
    }

    @Test
    fun `webhook renewal - a fresh event extends the period`() {
        val existing = sub(SubscriptionPlan.TRIO, SubscriptionStatus.ACTIVE, token = "tok")
        val newEnd = existing.currentPeriodEnd.plusDays(30)
        every { provider.parseWebhook(any(), any()) } returns WebhookResult.RenewalSucceeded("evt-2", "tok", newEnd)
        every { repository.findByProviderToken("tok") } returns existing
        every { repository.recordEventIfNew(existing.id, "evt-2", any()) } returns true

        service().handleWebhook("{}", null)

        verify { repository.extendPeriod(existing.id, newEnd) }
    }
}
