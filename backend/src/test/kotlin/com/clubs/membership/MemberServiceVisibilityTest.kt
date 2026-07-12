package com.clubs.membership

import com.clubs.application.ApplicationRepository
import com.clubs.award.AwardService
import com.clubs.common.auth.ClubManagerGuard
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.reputation.ClubReputationSummary
import com.clubs.reputation.Reputation
import com.clubs.reputation.ReputationRepository
import com.clubs.reputation.TrustService
import com.clubs.interest.InterestRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Асимметричная видимость Trust (reputation-path-back.md AC-3/AC-4/AC-5): оценочные метрики видят
 * только организатор и сам участник о себе; рядовому зрителю чужие числа не отдаются, а порядок
 * ростера для него нейтральный (никакого «дна таблицы»).
 */
class MemberServiceVisibilityTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var userRepository: UserRepository
    private lateinit var reputationRepository: ReputationRepository
    private lateinit var trustService: TrustService
    private lateinit var interestRepository: InterestRepository
    private lateinit var awardService: AwardService
    private lateinit var applicationRepository: ApplicationRepository
    private lateinit var service: MemberService

    private val clubId = UUID.randomUUID()
    private val organizerId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()   // рядовой участник-зритель
    private val otherId = UUID.randomUUID()    // другой участник (чужие числа должны быть скрыты)
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-05T12:00:00Z")

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        reputationRepository = mockk(relaxed = true)
        trustService = mockk(relaxed = true)
        interestRepository = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        applicationRepository = mockk(relaxed = true)
        service = MemberService(
            membershipRepository, userRepository, reputationRepository, trustService,
            interestRepository, awardService, applicationRepository, MembershipMapper(),
            ClubManagerGuard(mockk(relaxed = true), membershipRepository)
        )
    }

    private fun membership(userId: UUID, role: MembershipRole) = Membership(
        id = UUID.randomUUID(), userId = userId, clubId = clubId,
        status = MembershipStatus.active, role = role,
        joinedAt = now.minusDays(100), subscriptionExpiresAt = null,
        createdAt = now.minusDays(100), updatedAt = now
    )

    private fun memberInfo(userId: UUID, role: MembershipRole, joinedDaysAgo: Long) = ClubMemberInfo(
        userId = userId, firstName = "U", lastName = null, avatarUrl = null,
        role = role, joinedAt = now.minusDays(joinedDaysAgo),
        promiseFulfillmentPct = BigDecimal("78"), totalConfirmations = 9,
        outcomeCount = 5, status = MembershipStatus.active, subscriptionExpiresAt = null
    )

    private fun mockRoster(caller: Membership, includeFrozen: Boolean) {
        every { membershipRepository.findByUserAndClub(caller.userId, clubId) } returns caller
        // any() вторым аргументом: у метода default-параметр now=OffsetDateTime.now() — точное
        // значение на момент стаба никогда не совпадёт со значением на момент вызова.
        every { trustService.trustForClubMembers(clubId, any()) } returns
            mapOf(callerId to 80, otherId to 60, organizerId to 90)
        every { membershipRepository.findClubMembersWithUserInfo(clubId, includeFrozen) } returns listOf(
            // Нарочно НЕ в ожидаемом порядке: other вступил раньше caller, организатор в середине.
            memberInfo(callerId, MembershipRole.member, joinedDaysAgo = 10),
            memberInfo(organizerId, MembershipRole.organizer, joinedDaysAgo = 200),
            memberInfo(otherId, MembershipRole.member, joinedDaysAgo = 50)
        )
    }

    @Test
    fun `member viewer sees only own trust and a neutral joinedAt order`() {
        mockRoster(membership(callerId, MembershipRole.member), includeFrozen = false)

        val members = service.getClubMembers(clubId, callerId)

        // Порядок: организатор первым, дальше по давности вступления (other раньше caller).
        assertEquals(listOf(organizerId, otherId, callerId), members.map { it.userId })
        val own = members.first { it.userId == callerId }
        val other = members.first { it.userId == otherId }
        val organizer = members.first { it.userId == organizerId }
        assertEquals(80, own.trust)                       // своя строка — видна
        assertNull(other.trust)                           // чужая — скрыта (неотличимо от новичка)
        assertNull(other.promiseFulfillmentPct)
        assertNull(other.totalConfirmations)
        assertNull(organizer.trust)
    }

    @Test
    fun `organizer viewer keeps full trust visibility and trust-desc order`() {
        mockRoster(membership(organizerId, MembershipRole.organizer), includeFrozen = true)

        val members = service.getClubMembers(clubId, organizerId)

        // Организатор первым (правило анти-фарма), дальше по Trust DESC: caller(80) > other(60).
        assertEquals(listOf(organizerId, callerId, otherId), members.map { it.userId })
        assertEquals(80, members.first { it.userId == callerId }.trust)
        assertEquals(60, members.first { it.userId == otherId }.trust)
    }

    // --- профиль участника (AC-5) ---

    private fun mockProfileTarget(targetId: UUID) {
        val user = mockk<UsersRecord>(relaxed = true)
        every { user.firstName } returns "Боб"
        every { userRepository.findById(targetId) } returns user
        every { membershipRepository.findByUserAndClub(targetId, clubId) } returns
            membership(targetId, MembershipRole.member)
        every { reputationRepository.findByUserAndClub(targetId, clubId) } returns Reputation(
            userId = targetId, clubId = clubId, reliabilityIndex = 50,
            promiseFulfillmentPct = BigDecimal("78"), totalConfirmations = 9,
            totalAttendances = 7, spontaneityCount = 1, outcomeCount = 5
        )
        every { trustService.clubSummary(targetId, clubId, any()) } returns
            ClubReputationSummary(trust = 60, skladchinaPaid = 3, skladchinaTotal = 3)
    }

    @Test
    fun `member viewing another member gets a newcomer-shaped card - no scores at all`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns
            membership(callerId, MembershipRole.member)
        mockProfileTarget(otherId)

        val profile = service.getMemberProfile(clubId, otherId, callerId)

        assertNull(profile.trust)
        assertNull(profile.promiseFulfillmentPct)
        assertNull(profile.totalAttendances)
        assertNull(profile.skladchinaPaid)
        assertEquals("Боб", profile.firstName)            // не-оценочное остаётся
    }

    @Test
    fun `member viewing own profile still sees own scores`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns
            membership(callerId, MembershipRole.member)
        mockProfileTarget(callerId)

        val profile = service.getMemberProfile(clubId, callerId, callerId)

        assertEquals(60, profile.trust)
        assertEquals(3, profile.skladchinaPaid)
    }

    @Test
    fun `organizer viewing a member sees scores as before`() {
        every { membershipRepository.findByUserAndClub(organizerId, clubId) } returns
            membership(organizerId, MembershipRole.organizer)
        mockProfileTarget(otherId)

        val profile = service.getMemberProfile(clubId, otherId, organizerId)

        assertEquals(60, profile.trust)
        assertEquals(BigDecimal("78"), profile.promiseFulfillmentPct)
    }
}
