package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import com.clubs.reputation.ReputationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Гейт ручного закрытия сбора (У-1, co-organizers): создатель ИЛИ менеджер клуба.
 * Раньше — «Only creator can close»; со-орг/владелец ведут любые сборы клуба.
 */
class SkladchinaLifecycleServiceTest {

    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var clubRoleGuard: ClubRoleGuard
    private lateinit var reputationService: ReputationService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var queryService: SkladchinaQueryService
    private lateinit var service: SkladchinaLifecycleService

    private val skladchinaId = UUID.randomUUID()
    private val clubId = UUID.randomUUID()
    private val creatorId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        skladchinaRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        clubRoleGuard = mockk()
        reputationService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        queryService = mockk(relaxed = true)
        service = SkladchinaLifecycleService(
            skladchinaRepository, clubRepository, clubRoleGuard, reputationService, eventPublisher, queryService
        )
        // По умолчанию вызывающий — не менеджер (fail-close); тесты переопределяют точечно.
        every { clubRoleGuard.hasCapability(any<java.util.UUID>(), any<java.util.UUID>(), any<com.clubs.common.auth.ClubCapability>()) } returns false
        every { skladchinaRepository.findById(skladchinaId) } returns skladchina()
        // Конкурентный claim проигран -> closeInternal делает no-op; фокус теста — только гейт.
        every { skladchinaRepository.claimClose(any(), any(), any(), any()) } returns false
    }

    private fun skladchina(): Skladchina = Skladchina(
        id = skladchinaId,
        clubId = clubId,
        creatorId = creatorId,
        title = "Бронь корта",
        description = null,
        rules = null,
        photoUrl = null,
        template = SkladchinaTemplate.custom,
        paymentMode = SkladchinaMode.fixed_equal,
        totalGoalKopecks = 100_000L,
        paymentLink = "https://bank.example/pay",
        paymentMethodNote = null,
        eventId = null,
        deadline = OffsetDateTime.now().plusDays(3),
        affectsReputation = false,
        status = SkladchinaStatus.active,
        closedAt = null,
        closedBy = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now()
    )

    @Test
    fun `creator can close manually`() {
        service.closeManually(skladchinaId, creatorId)
        verify(exactly = 1) { skladchinaRepository.claimClose(skladchinaId, any(), creatorId, any()) }
    }

    @Test
    fun `club manager who is not the creator can close manually (У-1)`() {
        val managerId = UUID.randomUUID()
        every { clubRoleGuard.hasCapability(clubId, managerId, any()) } returns true

        service.closeManually(skladchinaId, managerId)

        verify(exactly = 1) { skladchinaRepository.claimClose(skladchinaId, any(), managerId, any()) }
    }

    @Test
    fun `stranger without a manager role cannot close (403, fail-close)`() {
        val strangerId = UUID.randomUUID()

        assertThrows<ForbiddenException> { service.closeManually(skladchinaId, strangerId) }
        verify(exactly = 0) { skladchinaRepository.claimClose(any(), any(), any(), any()) }
    }
}
