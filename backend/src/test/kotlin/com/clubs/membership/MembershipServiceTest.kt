package com.clubs.membership

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.payment.PaymentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MembershipServiceTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var clubRepository: ClubRepository
    private lateinit var paymentService: PaymentService
    private lateinit var dsl: DSLContext
    private lateinit var membershipService: MembershipService

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        paymentService = mockk(relaxed = true)
        dsl = mockk(relaxed = true)
        membershipService = MembershipService(membershipRepository, clubRepository, paymentService, dsl)
    }

    private fun freeClubRecord(clubId: UUID, ownerId: UUID = UUID.randomUUID(), memberLimit: Int = 50, memberCount: Int = 5): ClubsRecord =
        ClubsRecord(
            id = clubId,
            ownerId = ownerId,
            name = "Open Club",
            description = "An open club",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = memberLimit,
            subscriptionPrice = 0,
            memberCount = memberCount,
            activityRating = 0,
            isActive = true
        )

    private fun paidClubRecord(clubId: UUID, price: Int = 500, memberLimit: Int = 50, memberCount: Int = 5): ClubsRecord =
        ClubsRecord(
            id = clubId,
            ownerId = UUID.randomUUID(),
            name = "Paid Club",
            description = "Stars-paid",
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = "Moscow",
            memberLimit = memberLimit,
            subscriptionPrice = price,
            memberCount = memberCount,
            activityRating = 0,
            isActive = true
        )

    private fun membershipRecord(userId: UUID, clubId: UUID): MembershipsRecord {
        val now = OffsetDateTime.now()
        return MembershipsRecord(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = MembershipStatus.active,
            role = MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30)
        )
    }

    @Test
    fun `joinOpenClub free creates membership and increments member_count`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = freeClubRecord(clubId)
        val membership = membershipRecord(userId, clubId)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 5
        every { membershipRepository.create(userId, clubId) } returns membership

        val result = membershipService.joinOpenClub(clubId, userId)

        val joined = assertIs<JoinResult.Joined>(result)
        assertEquals(userId, joined.membership.userId)
        assertEquals(clubId, joined.membership.clubId)
        assertEquals("active", joined.membership.status)
        assertEquals("member", joined.membership.role)
        assertNotNull(joined.membership.joinedAt)
        verify(exactly = 1) { membershipRepository.create(userId, clubId) }
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `joinOpenClub paid sends invoice and does not create membership`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = paidClubRecord(clubId, price = 500)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 5

        val result = membershipService.joinOpenClub(clubId, userId)

        val pending = assertIs<JoinResult.PendingPayment>(result)
        assertEquals(clubId, pending.dto.clubId)
        assertEquals(500, pending.dto.priceStars)
        assertEquals("pending_payment", pending.dto.status)
        verify(exactly = 1) { paymentService.createInvoice(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { clubRepository.incrementMemberCount(any()) }
    }

    @Test
    fun `joinOpenClub throws NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club not found", exception.message)
    }

    @Test
    fun `joinOpenClub throws ValidationException when club is not open`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val closedClub = ClubsRecord(
            id = clubId,
            ownerId = UUID.randomUUID(),
            name = "Closed Club",
            description = "Closed",
            category = ClubCategory.sport,
            accessType = AccessType.closed,
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 0,
            memberCount = 0,
            activityRating = 0,
            isActive = true
        )

        every { clubRepository.findById(clubId) } returns closedClub

        val exception = assertThrows<ValidationException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club is not open for joining", exception.message)
    }

    @Test
    fun `joinOpenClub throws ConflictException when user is already a member`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = freeClubRecord(clubId)
        val existingMembership = membershipRecord(userId, clubId)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns existingMembership

        val exception = assertThrows<ConflictException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Already a member", exception.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `joinOpenClub throws ValidationException when club is full`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = freeClubRecord(clubId, memberLimit = 20, memberCount = 20)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 20

        val exception = assertThrows<ValidationException> {
            membershipService.joinOpenClub(clubId, userId)
        }

        assertEquals("Club is full", exception.message)
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `joinOpenClub paid repeated click sends invoice again (idempotent 202)`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = paidClubRecord(clubId, price = 500)

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 5

        val first = membershipService.joinOpenClub(clubId, userId)
        val second = membershipService.joinOpenClub(clubId, userId)

        assertIs<JoinResult.PendingPayment>(first)
        assertIs<JoinResult.PendingPayment>(second)
        verify(exactly = 2) { paymentService.createInvoice(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `joinByInviteCode paid sends invoice`() {
        val code = "INV-XYZ"
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = paidClubRecord(clubId, price = 300)

        every { clubRepository.findByInviteCode(code) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 0

        val result = membershipService.joinByInviteCode(code, userId)

        val pending = assertIs<JoinResult.PendingPayment>(result)
        assertEquals(300, pending.dto.priceStars)
        verify(exactly = 1) { paymentService.createInvoice(userId, clubId) }
        verify(exactly = 0) { membershipRepository.create(any(), any()) }
    }

    @Test
    fun `joinByInviteCode free creates membership`() {
        val code = "INV-FREE"
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val club = freeClubRecord(clubId)
        val membership = membershipRecord(userId, clubId)

        every { clubRepository.findByInviteCode(code) } returns club
        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null
        every { membershipRepository.countActiveByClubId(clubId) } returns 0
        every { membershipRepository.create(userId, clubId) } returns membership

        val result = membershipService.joinByInviteCode(code, userId)

        assertIs<JoinResult.Joined>(result)
        verify(exactly = 1) { clubRepository.incrementMemberCount(clubId) }
        verify(exactly = 0) { paymentService.createInvoice(any(), any()) }
    }

    @Test
    fun `joinByInviteCode throws NotFoundException for unknown code`() {
        every { clubRepository.findByInviteCode("bad") } returns null

        val exception = assertThrows<NotFoundException> {
            membershipService.joinByInviteCode("bad", UUID.randomUUID())
        }

        assertEquals("Invite link not found", exception.message)
    }

    @Test
    fun `cancelMembership marks membership as cancelled`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val membership = membershipRecord(userId, clubId)

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns membership

        val result = membershipService.cancelMembership(clubId, userId)

        assertEquals("cancelled", result.status)
        assertEquals(userId, result.userId)
        assertEquals(clubId, result.clubId)
    }

    @Test
    fun `cancelMembership throws NotFoundException when membership does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            membershipService.cancelMembership(clubId, userId)
        }

        assertEquals("Membership not found", exception.message)
    }

    @Test
    fun `cancelMembership throws ValidationException when already cancelled`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val cancelledMembership = MembershipsRecord(
            id = UUID.randomUUID(),
            userId = userId,
            clubId = clubId,
            status = MembershipStatus.cancelled,
            role = MembershipRole.member,
            joinedAt = now,
            subscriptionExpiresAt = now.plusDays(30)
        )

        every { membershipRepository.findByUserAndClub(userId, clubId) } returns cancelledMembership

        val exception = assertThrows<ValidationException> {
            membershipService.cancelMembership(clubId, userId)
        }

        assertEquals("Membership already cancelled", exception.message)
    }
}
