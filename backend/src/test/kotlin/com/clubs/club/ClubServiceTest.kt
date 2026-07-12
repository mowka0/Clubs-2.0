package com.clubs.club

import com.clubs.common.exception.ConflictException
import com.clubs.common.auth.ClubRoleGuard
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.application.ApplicationRepository
import com.clubs.event.EventRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.membership.MembershipRepository
import com.clubs.skladchina.SkladchinaRepository
import com.clubs.subscription.SubscriptionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class ClubServiceTest {

    private lateinit var clubRepository: ClubRepository
    private lateinit var membershipRepository: MembershipRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var skladchinaRepository: SkladchinaRepository
    private lateinit var applicationRepository: ApplicationRepository
    private lateinit var subscriptionService: SubscriptionService
    private lateinit var mapper: ClubMapper
    private lateinit var clubService: ClubService

    @BeforeEach
    fun setUp() {
        clubRepository = mockk(relaxed = true)
        membershipRepository = mockk(relaxed = true)
        eventRepository = mockk(relaxed = true)
        skladchinaRepository = mockk(relaxed = true)
        applicationRepository = mockk(relaxed = true)
        subscriptionService = mockk(relaxed = true)
        mapper = ClubMapper()
        clubService = ClubService(clubRepository, membershipRepository, ClubRoleGuard(clubRepository, membershipRepository), eventRepository, skladchinaRepository, applicationRepository, subscriptionService, chatLinkRepository = mockk(relaxed = true), userRepository = mockk(relaxed = true), mapper = mapper)
    }

    private fun makeClub(
        clubId: UUID = UUID.randomUUID(),
        ownerId: UUID = UUID.randomUUID(),
        name: String = "Club",
        description: String = "Desc",
        category: ClubCategory = ClubCategory.sport,
        accessType: AccessType = AccessType.`open`,
        city: String = "Moscow",
        memberLimit: Int = 30,
        subscriptionPrice: Int = 0,
        memberCount: Int = 3
    ): Club {
        val now = OffsetDateTime.now()
        return Club(
            id = clubId,
            ownerId = ownerId,
            name = name,
            description = description,
            category = category,
            accessType = accessType,
            city = city,
            district = null,
            memberLimit = memberLimit,
            subscriptionPrice = subscriptionPrice,
            avatarUrl = null,
            rules = null,
            applicationQuestion = null,
            inviteLink = null,
            memberCount = memberCount,
            isActive = true,
            paymentLink = null,
            paymentMethodNote = null,
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `createClub should return club detail when request is valid`() {
        val ownerId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "A test club description",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100,
            paymentLink = "https://sbp.example/pay"
        )

        val club = makeClub(
            clubId = clubId,
            ownerId = ownerId,
            name = request.name,
            description = request.description,
            category = ClubCategory.sport,
            accessType = AccessType.`open`,
            city = request.city,
            memberLimit = request.memberLimit,
            subscriptionPrice = request.subscriptionPrice,
            memberCount = 0
        )

        every { clubRepository.countByOwnerId(ownerId) } returns 0
        every { clubRepository.create(request, ownerId, any()) } returns club
        // createClub re-reads via findById so the response carries the live member count.
        every { clubRepository.findById(clubId) } returns club

        val result = clubService.createClub(request, ownerId)

        assertEquals(clubId, result.id)
        assertEquals("Test Club", result.name)
        assertEquals("sport", result.category)
        assertEquals("open", result.accessType)
        assertEquals("Moscow", result.city)
        assertEquals(50, result.memberLimit)
        assertEquals(100, result.subscriptionPrice)
        verify(exactly = 1) { clubRepository.create(request, ownerId, any()) }
        verify(exactly = 1) { membershipRepository.createOrganizer(ownerId, clubId) }
    }

    @Test
    fun `createClub should throw ValidationException when category is invalid`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "invalid_category",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        val exception = assertThrows<ValidationException> {
            clubService.createClub(request, ownerId)
        }

        assertEquals("Invalid category: invalid_category", exception.message)
        verify(exactly = 0) { clubRepository.create(any(), any(), any()) }
    }

    @Test
    fun `createClub should throw ValidationException when access type is invalid`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "sport",
            accessType = "nonexistent",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        val exception = assertThrows<ValidationException> {
            clubService.createClub(request, ownerId)
        }

        assertEquals("Invalid access type: nonexistent", exception.message)
    }

    @Test
    fun `createClub should throw ConflictException when owner has 10 clubs`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Test Club",
            description = "Description",
            category = "sport",
            accessType = "open",
            city = "Moscow",
            memberLimit = 50,
            subscriptionPrice = 100
        )

        every { clubRepository.countByOwnerId(ownerId) } returns 10

        val exception = assertThrows<ConflictException> {
            clubService.createClub(request.copy(paymentLink = "https://sbp.example/pay"), ownerId)
        }

        assertEquals("Maximum 10 clubs per organizer", exception.message)
        verify(exactly = 0) { clubRepository.create(any(), any(), any()) }
    }

    @Test
    fun `createClub rejects a paid club without payment requisites`() {
        val ownerId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Paid Club", description = "Desc", category = "sport", accessType = "open",
            city = "Moscow", memberLimit = 30, subscriptionPrice = 100 // no paymentLink
        )

        val exception = assertThrows<ValidationException> { clubService.createClub(request, ownerId) }

        assertEquals("Для платного клуба укажите реквизиты для взноса (СБП)", exception.message)
        verify(exactly = 0) { clubRepository.create(any(), any(), any()) }
    }

    @Test
    fun `createClub allows a free club without requisites`() {
        val ownerId = UUID.randomUUID()
        val clubId = UUID.randomUUID()
        val request = CreateClubRequest(
            name = "Free Club", description = "Desc", category = "sport", accessType = "open",
            city = "Moscow", memberLimit = 30, subscriptionPrice = 0 // free → no link needed
        )
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 0)
        every { clubRepository.countByOwnerId(ownerId) } returns 0
        every { clubRepository.create(request, ownerId, any()) } returns club
        every { clubRepository.findById(clubId) } returns club

        val result = clubService.createClub(request, ownerId)

        assertEquals(clubId, result.id)
        verify(exactly = 1) { clubRepository.create(request, ownerId, any()) }
    }

    @Test
    fun `updateClub rejects clearing the link on a paid club`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 100)
            .copy(paymentLink = "https://sbp.example/pay")
        every { clubRepository.findById(clubId) } returns club

        // Blank string = "clear to NULL" — not allowed while the club stays paid.
        val exception = assertThrows<ValidationException> {
            clubService.updateClub(clubId, UpdateClubRequest(paymentLink = ""), ownerId)
        }

        assertEquals("Для платного клуба укажите реквизиты для взноса (СБП)", exception.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `updateClub rejects switching free to paid without a link`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 0) // free, no link
        every { clubRepository.findById(clubId) } returns club

        val exception = assertThrows<ValidationException> {
            clubService.updateClub(clubId, UpdateClubRequest(subscriptionPrice = 100), ownerId)
        }

        assertEquals("Для платного клуба укажите реквизиты для взноса (СБП)", exception.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `updateClub allows a paid club edit that keeps the existing link`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 100)
            .copy(paymentLink = "https://sbp.example/pay")
        val updated = club.copy(name = "Renamed")
        every { clubRepository.findById(clubId) } returns club
        every { clubRepository.update(clubId, any()) } returns updated

        // Editing the name (link key absent → kept) must not trip the requisites invariant.
        val result = clubService.updateClub(clubId, UpdateClubRequest(name = "Renamed"), ownerId)

        assertEquals("Renamed", result.name)
        verify(exactly = 1) { clubRepository.update(clubId, any()) }
    }

    @Test
    fun `getClub should throw NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()

        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            clubService.getClub(clubId, UUID.randomUUID())
        }

        assertEquals("Club not found", exception.message)
    }

    @Test
    fun `getClub should return club detail when club exists`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val club = makeClub(
            clubId = clubId,
            ownerId = ownerId,
            name = "Existing Club",
            description = "Exists",
            category = ClubCategory.creative,
            accessType = AccessType.closed,
            city = "SPb",
            memberLimit = 20,
            subscriptionPrice = 200,
            memberCount = 5
        )

        every { clubRepository.findById(clubId) } returns club

        val result = clubService.getClub(clubId, ownerId)

        assertEquals(clubId, result.id)
        assertEquals("Existing Club", result.name)
        assertEquals("creative", result.category)
        assertEquals(5, result.memberCount)
    }

    @Test
    fun `updateClub should throw ForbiddenException when user is not owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val differentUserId = UUID.randomUUID()

        val club = makeClub(clubId = clubId, ownerId = ownerId, name = "My Club")

        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(differentUserId, clubId) } returns null

        val updateRequest = UpdateClubRequest(name = "New Name")

        val exception = assertThrows<ForbiddenException> {
            clubService.updateClub(clubId, updateRequest, differentUserId)
        }

        assertEquals("Управлять клубом может владелец или активный со-организатор", exception.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `updateClub should succeed when user is the owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()

        val club = makeClub(clubId = clubId, ownerId = ownerId, name = "My Club")
        val updatedClub = club.copy(name = "Updated Club")

        every { clubRepository.findById(clubId) } returns club
        every { clubRepository.update(clubId, any()) } returns updatedClub

        val updateRequest = UpdateClubRequest(name = "Updated Club")
        val result = clubService.updateClub(clubId, updateRequest, ownerId)

        assertEquals("Updated Club", result.name)
        verify(exactly = 1) { clubRepository.update(clubId, updateRequest) }
    }

    @Test
    fun `deleteClub soft-deletes and cancels the club's live events, skladchinas and applications`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns makeClub(clubId = clubId, ownerId = ownerId)

        clubService.deleteClub(clubId, ownerId)

        // Cascade runs before the soft-delete, scoped to this club only.
        verify(exactly = 1) { eventRepository.cancelActiveEventsByClub(clubId) }
        verify(exactly = 1) { skladchinaRepository.cancelActiveByClub(clubId) }
        verify(exactly = 1) { applicationRepository.deleteActiveByClub(clubId) }
        verify(exactly = 1) { clubRepository.softDelete(clubId) }
    }

    @Test
    fun `deleteClub throws ForbiddenException when user is not owner`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns makeClub(clubId = clubId, ownerId = ownerId)

        val exception = assertThrows<ForbiddenException> {
            clubService.deleteClub(clubId, otherUserId)
        }

        assertEquals("Only the club owner can delete it", exception.message)
        // A non-owner triggers no cascade and no delete.
        verify(exactly = 0) { eventRepository.cancelActiveEventsByClub(any()) }
        verify(exactly = 0) { skladchinaRepository.cancelActiveByClub(any()) }
        verify(exactly = 0) { applicationRepository.deleteActiveByClub(any()) }
        verify(exactly = 0) { clubRepository.softDelete(any()) }
    }

    @Test
    fun `deleteClub throws NotFoundException when club does not exist`() {
        val clubId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        every { clubRepository.findById(clubId) } returns null

        val exception = assertThrows<NotFoundException> {
            clubService.deleteClub(clubId, userId)
        }

        assertEquals("Club not found", exception.message)
        verify(exactly = 0) { eventRepository.cancelActiveEventsByClub(any()) }
        verify(exactly = 0) { skladchinaRepository.cancelActiveByClub(any()) }
        verify(exactly = 0) { applicationRepository.deleteActiveByClub(any()) }
        verify(exactly = 0) { clubRepository.softDelete(any()) }
    }

    // --- co-organizers, Security-фиксы: СБП-реквизиты owner-only + пейволл по владельцу ---

    private fun coOrgMembership(userId: UUID, clubId: UUID) = com.clubs.membership.Membership(
        id = UUID.randomUUID(), userId = userId, clubId = clubId,
        status = com.clubs.generated.jooq.enums.MembershipStatus.active,
        role = com.clubs.generated.jooq.enums.MembershipRole.co_organizer,
        joinedAt = OffsetDateTime.now(), subscriptionExpiresAt = null,
        createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now()
    )

    @Test
    fun `co-org cannot change the SBP requisites (403)`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val coOrgId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId).copy(paymentLink = "sbp://old")
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns coOrgMembership(coOrgId, clubId)

        val ex = assertThrows<ForbiddenException> {
            clubService.updateClub(clubId, UpdateClubRequest(paymentLink = "sbp://new"), coOrgId)
        }

        assertEquals("Реквизиты для взноса задаёт владелец клуба", ex.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `co-org cannot change the payment method note (403)`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val coOrgId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId).copy(paymentMethodNote = "перевод по номеру телефона")
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns coOrgMembership(coOrgId, clubId)

        // paymentMethodNote — второй независимый операнд requisitesChanged: регресс в его
        // нормализации не должен пройти мимо тестов.
        val ex = assertThrows<ForbiddenException> {
            clubService.updateClub(clubId, UpdateClubRequest(paymentMethodNote = "на карту 1234"), coOrgId)
        }

        assertEquals("Реквизиты для взноса задаёт владелец клуба", ex.message)
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `co-org updates other settings, including a no-op requisites resubmit`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val coOrgId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId).copy(paymentLink = "sbp://same")
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns coOrgMembership(coOrgId, clubId)
        every { clubRepository.update(clubId, any()) } returns club.copy(name = "Renamed")

        // Поле прислано, но значение то же — no-op сабмит не должен блокироваться.
        val result = clubService.updateClub(
            clubId, UpdateClubRequest(name = "Renamed", paymentLink = "sbp://same"), coOrgId
        )

        assertEquals("Renamed", result.name)
        verify(exactly = 1) { clubRepository.update(clubId, any()) }
    }

    @Test
    fun `owner updates the requisites freely`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId).copy(paymentLink = "sbp://old")
        every { clubRepository.findById(clubId) } returns club
        every { clubRepository.update(clubId, any()) } returns club.copy(paymentLink = "sbp://new")

        clubService.updateClub(clubId, UpdateClubRequest(paymentLink = "sbp://new"), ownerId)

        verify(exactly = 1) { clubRepository.update(clubId, any()) }
    }

    @Test
    fun `co-org cannot flip a club free-to-paid (owner-only, 403)`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val coOrgId = UUID.randomUUID()
        // Легаси-реквизиты уже заданы владельцем: со-орг пытается лишь поднять цену 0 -> >0.
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 0).copy(paymentLink = "sbp://x")
        every { clubRepository.findById(clubId) } returns club
        every { membershipRepository.findByUserAndClub(coOrgId, clubId) } returns coOrgMembership(coOrgId, clubId)

        val ex = assertThrows<ForbiddenException> {
            clubService.updateClub(clubId, UpdateClubRequest(subscriptionPrice = 500), coOrgId)
        }

        // Перевод в платный — владельческое (EDIT_PAYMENT_REQUISITES). Гейт бьёт ДО пейволла и апдейта.
        assertEquals("Перевести клуб в платный может только владелец", ex.message)
        verify(exactly = 0) { subscriptionService.requirePaidClubCapacity(any(), any()) }
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }

    @Test
    fun `owner flipping free-to-paid hits the plan paywall on the owner (402)`() {
        val clubId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val club = makeClub(clubId = clubId, ownerId = ownerId, subscriptionPrice = 0).copy(paymentLink = "sbp://x")
        every { clubRepository.findById(clubId) } returns club
        every { clubRepository.countPaidByOwnerId(ownerId) } returns 3
        every { subscriptionService.requirePaidClubCapacity(ownerId, any()) } throws
            com.clubs.common.exception.PaymentRequiredException("free", "start", 19900)

        assertThrows<com.clubs.common.exception.PaymentRequiredException> {
            clubService.updateClub(clubId, UpdateClubRequest(subscriptionPrice = 500), ownerId)
        }

        // Ёмкость плана считается по владельцу клуба.
        verify(exactly = 1) { subscriptionService.requirePaidClubCapacity(ownerId, 3) }
        verify(exactly = 0) { clubRepository.update(any(), any()) }
    }
}
