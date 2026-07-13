package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.skladchina.template.DeclinePolicy
import com.clubs.skladchina.template.SkladchinaTemplateRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Публикация [SkladchinaProgressChangedEvent] из мутаций платёжного сервиса — контракт
 * «живого статуса сбора» (слайс 3.5): удаление любой publish-строки должно валить тест
 * (дыра покрытия GAP-2 из QA-отчёта слайса).
 */
class SkladchinaPaymentServiceTest {

    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var clubRoleGuard: ClubRoleGuard
    private lateinit var templateRegistry: SkladchinaTemplateRegistry
    private lateinit var queryService: SkladchinaQueryService
    private lateinit var lifecycleService: SkladchinaLifecycleService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: SkladchinaPaymentService

    private val skladchinaId = UUID.randomUUID()
    private val creatorId = UUID.randomUUID()
    private val participantId = UUID.randomUUID()

    private fun skladchina(
        template: SkladchinaTemplate = SkladchinaTemplate.custom,
        deadline: OffsetDateTime = OffsetDateTime.now().plusDays(3)
    ): Skladchina = Skladchina(
        id = skladchinaId,
        clubId = UUID.randomUUID(),
        creatorId = creatorId,
        title = "Бронь корта",
        description = null,
        rules = null,
        photoUrl = null,
        template = template,
        paymentMode = SkladchinaMode.fixed_equal,
        totalGoalKopecks = 100_000L,
        paymentLink = "https://bank.example/pay",
        paymentMethodNote = null,
        eventId = null,
        deadline = deadline,
        affectsReputation = false,
        status = SkladchinaStatus.active,
        closedAt = null,
        closedBy = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    private fun participant(
        status: SkladchinaParticipantStatus = SkladchinaParticipantStatus.pending,
        declineRequestedAt: OffsetDateTime? = null
    ): SkladchinaParticipant = SkladchinaParticipant(
        skladchinaId = skladchinaId,
        userId = participantId,
        expectedAmountKopecks = 50_000L,
        declaredAmountKopecks = null,
        status = status,
        paidAt = null,
        declinedAt = null,
        reputationApplied = false,
        declineNote = null,
        declineRequestedAt = declineRequestedAt,
        declineRejected = false,
        createdAt = OffsetDateTime.now()
    )

    @BeforeEach
    fun setUp() {
        skladchinaRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        templateRegistry = mockk()
        queryService = mockk()
        lifecycleService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        clubRoleGuard = mockk()
        // По умолчанию вызывающий — НЕ менеджер клуба: creator-путь guard не трогает, а
        // не-создатель без роли должен получать 403 (fail-close).
        every { clubRoleGuard.hasCapability(any<java.util.UUID>(), any<java.util.UUID>(), any<com.clubs.common.auth.ClubCapability>()) } returns false
        service = SkladchinaPaymentService(
            skladchinaRepository, clubRepository, clubRoleGuard, templateRegistry, queryService,
            lifecycleService, eventPublisher
        )
        every { queryService.getDetail(any(), any()) } returns mockk()
        every { templateRegistry.forType(any()) } returns mockk {
            every { declinePolicy } returns DeclinePolicy.FREE
        }
    }

    @Test
    fun `markPaid публикует SkladchinaProgressChangedEvent`() {
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.setParticipantPaid(skladchinaId, participantId, any(), any()) } returns 1

        service.markPaid(skladchinaId, participantId, declaredAmountKopecks = null)

        verify { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }
    }

    @Test
    fun `markPaid идемпотентный повтор (уже paid) — событие НЕ публикуется`() {
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns
            participant(status = SkladchinaParticipantStatus.paid)

        service.markPaid(skladchinaId, participantId, declaredAmountKopecks = null)

        // ofType, не any: any<T>() в MockK не проверяет тип и посчитал бы посторонние события.
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<SkladchinaProgressChangedEvent>()) }
    }

    @Test
    fun `decline публикует SkladchinaProgressChangedEvent`() {
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.setParticipantDeclined(skladchinaId, participantId, any()) } returns 1

        service.decline(skladchinaId, participantId)

        verify { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }
    }

    @Test
    fun `organizerMarkPaid и organizerUnmarkPaid публикуют SkladchinaProgressChangedEvent`() {
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.setParticipantPaid(skladchinaId, participantId, any(), any()) } returns 1

        service.organizerMarkPaid(skladchinaId, creatorId, participantId)
        verify(exactly = 1) { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }

        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns
            participant(status = SkladchinaParticipantStatus.paid)
        every { skladchinaRepository.revertParticipantToPending(skladchinaId, participantId) } returns 1

        service.organizerUnmarkPaid(skladchinaId, creatorId, participantId)
        verify(exactly = 2) { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }
    }

    @Test
    fun `resolveDecline approve публикует SkladchinaProgressChangedEvent`() {
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina(template = SkladchinaTemplate.split_bill)
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns
            participant(declineRequestedAt = OffsetDateTime.now())
        every { skladchinaRepository.setParticipantDeclined(skladchinaId, participantId, any()) } returns 1

        service.resolveDecline(skladchinaId, creatorId, participantId, approve = true, rejectReason = null)

        verify { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }
    }

    @Test
    fun `requestDecline со сдвигом дедлайна публикует SkladchinaProgressChangedEvent`() {
        // Дедлайн ближе 48ч → extendDeadline вернёт 1 (сдвинут) → строка «⏳ До» в чате изменилась.
        every { skladchinaRepository.findById(skladchinaId) } returns
            skladchina(template = SkladchinaTemplate.split_bill, deadline = OffsetDateTime.now().plusHours(10))
        every { templateRegistry.forType(SkladchinaTemplate.split_bill) } returns mockk {
            every { declinePolicy } returns DeclinePolicy.REQUIRES_APPROVAL
        }
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.requestDecline(skladchinaId, participantId, any(), any()) } returns 1
        every { skladchinaRepository.extendDeadline(skladchinaId, any()) } returns 1
        every { clubRepository.findById(any()) } returns null

        service.requestDecline(skladchinaId, participantId, reason = "не смогу")

        verify { eventPublisher.publishEvent(SkladchinaProgressChangedEvent(skladchinaId)) }
    }

    @Test
    fun `requestDecline без сдвига дедлайна — SkladchinaProgressChangedEvent НЕ публикуется`() {
        every { skladchinaRepository.findById(skladchinaId) } returns
            skladchina(template = SkladchinaTemplate.split_bill, deadline = OffsetDateTime.now().plusDays(10))
        every { templateRegistry.forType(SkladchinaTemplate.split_bill) } returns mockk {
            every { declinePolicy } returns DeclinePolicy.REQUIRES_APPROVAL
        }
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.requestDecline(skladchinaId, participantId, any(), any()) } returns 1
        every { skladchinaRepository.extendDeadline(skladchinaId, any()) } returns 0
        every { clubRepository.findById(any()) } returns null

        service.requestDecline(skladchinaId, participantId, reason = "не смогу")

        // ofType, не any: этот путь легитимно публикует SkladchinaDeclineRequestedEvent.
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<SkladchinaProgressChangedEvent>()) }
    }

    // --- creator | manager (У-1, co-organizers) ---

    @Test
    fun `organizerMarkPaid допускает менеджера клуба, который не создатель`() {
        val managerId = UUID.randomUUID()
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns participant()
        every { skladchinaRepository.setParticipantPaid(skladchinaId, participantId, any(), any()) } returns 1
        every { clubRoleGuard.hasCapability(any<java.util.UUID>(), managerId, any<com.clubs.common.auth.ClubCapability>()) } returns true

        service.organizerMarkPaid(skladchinaId, managerId, participantId)

        verify(exactly = 1) { skladchinaRepository.setParticipantPaid(skladchinaId, participantId, any(), any()) }
    }

    @Test
    fun `organizerMarkPaid отклоняет не-создателя без менеджерской роли (403, fail-close)`() {
        val strangerId = UUID.randomUUID()
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        // setUp: clubRoleGuard.hasCapability(any<java.util.UUID>(), any<java.util.UUID>(), any<com.clubs.common.auth.ClubCapability>()) == false

        org.junit.jupiter.api.assertThrows<com.clubs.common.exception.ForbiddenException> {
            service.organizerMarkPaid(skladchinaId, strangerId, participantId)
        }
        verify(exactly = 0) { skladchinaRepository.setParticipantPaid(any(), any(), any(), any()) }
    }

    @Test
    fun `resolveDecline допускает менеджера клуба, который не создатель`() {
        val managerId = UUID.randomUUID()
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        every { skladchinaRepository.findParticipant(skladchinaId, participantId) } returns
            participant(declineRequestedAt = OffsetDateTime.now())
        every { skladchinaRepository.setParticipantDeclined(skladchinaId, participantId, any()) } returns 1
        every { clubRoleGuard.hasCapability(any<java.util.UUID>(), managerId, any<com.clubs.common.auth.ClubCapability>()) } returns true

        service.resolveDecline(skladchinaId, managerId, participantId, approve = true, rejectReason = null)

        verify(exactly = 1) { skladchinaRepository.setParticipantDeclined(skladchinaId, participantId, any()) }
    }
}
