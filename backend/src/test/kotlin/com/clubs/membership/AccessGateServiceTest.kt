package com.clubs.membership

import com.clubs.common.auth.ClubManagerGuard
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class AccessGateServiceTest {

    private lateinit var membershipRepository: MembershipRepository
    private lateinit var userRepository: com.clubs.user.UserRepository
    private lateinit var clubRepository: com.clubs.club.ClubRepository
    private lateinit var applicationRepository: com.clubs.application.ApplicationRepository
    private lateinit var eventResponseRepository: com.clubs.event.EventResponseRepository
    private lateinit var skladchinaRepository: com.clubs.skladchina.SkladchinaRepository
    private lateinit var notificationService: com.clubs.bot.NotificationService
    private lateinit var eventPublisher: org.springframework.context.ApplicationEventPublisher
    private lateinit var service: AccessGateService

    private val clubId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()
    private val callerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        membershipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        clubRepository = mockk(relaxed = true)
        applicationRepository = mockk(relaxed = true)
        eventResponseRepository = mockk(relaxed = true)
        skladchinaRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        // Empty base-url mirrors production (uploader returns root-relative "/uploads/...").
        service = AccessGateService(
            membershipRepository, MembershipMapper(), accessPeriodDays = 30, storageBaseUrl = "",
            userRepository = userRepository, clubRepository = clubRepository,
            applicationRepository = applicationRepository,
            eventResponseRepository = eventResponseRepository,
            skladchinaRepository = skladchinaRepository,
            notificationService = notificationService,
            eventPublisher = eventPublisher,
            clubManagerGuard = ClubManagerGuard(clubRepository, membershipRepository)
        )
        // По умолчанию вызывающий — владелец клуба (target-матрица owner-пути); co-org-тесты переопределяют.
        every { clubRepository.findById(clubId) } returns club(ownerId = callerId)
    }

    private fun club(ownerId: UUID): com.clubs.club.Club {
        val now = OffsetDateTime.now()
        return com.clubs.club.Club(
            id = clubId,
            ownerId = ownerId,
            name = "Club",
            description = "d",
            category = com.clubs.generated.jooq.enums.ClubCategory.sport,
            accessType = com.clubs.generated.jooq.enums.AccessType.`open`,
            city = "Moscow",
            district = null,
            memberLimit = 30,
            subscriptionPrice = 100,
            avatarUrl = null,
            rules = null,
            applicationQuestion = null,
            inviteLink = null,
            memberCount = 3,
            isActive = true,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun membership(status: MembershipStatus, role: MembershipRole = MembershipRole.member): Membership {
        val now = OffsetDateTime.now()
        return Membership(
            id = UUID.randomUUID(),
            userId = targetUserId,
            clubId = clubId,
            status = status,
            role = role,
            joinedAt = now,
            subscriptionExpiresAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    // --- freezeAccess ---

    @Test
    fun `freezeAccess freezes an active member`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 1

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
        verify(exactly = 1) { membershipRepository.freezeAccess(m.id) }
    }

    @Test
    fun `freezeAccess is idempotent when already frozen`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.frozen)

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess rejects a non-active member`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.cancelled)

        assertThrows<ValidationException> { service.freezeAccess(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess rejects managing the organizer`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            membership(MembershipStatus.active, role = MembershipRole.organizer)

        assertThrows<ValidationException> { service.freezeAccess(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `freezeAccess throws Conflict when the guarded update affects 0 rows (lost race)`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 0

        assertThrows<ConflictException> { service.freezeAccess(clubId, targetUserId, callerId) }
    }

    @Test
    fun `freezeAccess throws NotFound when the member does not exist`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns null

        assertThrows<NotFoundException> { service.freezeAccess(clubId, targetUserId, callerId) }
    }

    @Test
    fun `freezeAccess DMs the frozen member a deep-link to pay`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 1
        val club = mockk<com.clubs.club.Club>(relaxed = true)
        every { club.name } returns "Бег по утрам"
        every { club.ownerId } returns callerId
        every { clubRepository.findById(clubId) } returns club
        val member = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 77L }
        every { userRepository.findById(targetUserId) } returns member

        service.freezeAccess(clubId, targetUserId, callerId)

        verify(exactly = 1) { notificationService.sendAccessFrozenDM(77L, "Бег по утрам", clubId) }
    }

    // --- unfreezeAccess ---

    @Test
    fun `unfreezeAccess unfreezes a frozen member`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.unfreezeAccess(m.id) } returns 1

        val result = service.unfreezeAccess(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.unfreezeAccess(m.id) }
    }

    @Test
    fun `unfreezeAccess is idempotent when already active`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)

        val result = service.unfreezeAccess(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 0) { membershipRepository.unfreezeAccess(any()) }
    }

    // --- markDuesPaid ---

    @Test
    fun `markDuesPaid grants access to a frozen member`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.markDuesPaid(m.id, callerId, any()) } returns 1

        val result = service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.markDuesPaid(m.id, callerId, any()) }
    }

    @Test
    fun `markDuesPaid is accepted for an already-active member`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.markDuesPaid(m.id, callerId, any()) } returns 1

        val result = service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.markDuesPaid(m.id, callerId, any()) }
    }

    @Test
    fun `markDuesPaid DMs the member the new access-until date (фидбек PO 2026-07-08)`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.markDuesPaid(m.id, callerId, any()) } returns 1
        val club = mockk<com.clubs.club.Club>(relaxed = true)
        every { club.name } returns "Бег по утрам"
        every { club.ownerId } returns callerId
        every { clubRepository.findById(clubId) } returns club
        val member = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 77L }
        every { userRepository.findById(targetUserId) } returns member

        service.markDuesPaid(clubId, targetUserId, callerId)

        verify(exactly = 1) { notificationService.sendAccessExtendedDM(77L, "Бег по утрам", clubId, any()) }
    }

    @Test
    fun `setAccessUntil DMs the member the manually set access-until date`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.setAccessUntil(m.id, any()) } returns 1
        val club = mockk<com.clubs.club.Club>(relaxed = true)
        every { club.name } returns "Бег по утрам"
        every { club.ownerId } returns callerId
        every { clubRepository.findById(clubId) } returns club
        val member = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 77L }
        every { userRepository.findById(targetUserId) } returns member
        val until = java.time.OffsetDateTime.now().plusDays(45)

        service.setAccessUntil(clubId, targetUserId, until, callerId)

        verify(exactly = 1) { notificationService.sendAccessExtendedDM(77L, "Бег по утрам", clubId, until) }
    }

    @Test
    fun `markDuesPaid rejects a non-member (cancelled)`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.cancelled)

        assertThrows<ValidationException> { service.markDuesPaid(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.markDuesPaid(any(), any(), any()) }
    }

    @Test
    fun `markDuesPaid extends access by one period from the current future expiry (no lost days)`() {
        // Option B: a member who pays early keeps their remaining days — extend from the current expiry.
        val currentExpiry = OffsetDateTime.now().plusDays(5)
        val m = membership(MembershipStatus.active).copy(subscriptionExpiresAt = currentExpiry)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        val captured = slot<OffsetDateTime>()
        every { membershipRepository.markDuesPaid(m.id, callerId, capture(captured)) } returns 1

        service.markDuesPaid(clubId, targetUserId, callerId)

        assertEquals(currentExpiry.plusDays(30), captured.captured)
    }

    @Test
    fun `markDuesPaid for a frozen member with no prior expiry extends from now`() {
        val m = membership(MembershipStatus.frozen) // subscriptionExpiresAt = null
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        val captured = slot<OffsetDateTime>()
        every { membershipRepository.markDuesPaid(m.id, callerId, capture(captured)) } returns 1
        val before = OffsetDateTime.now()

        service.markDuesPaid(clubId, targetUserId, callerId)

        // ~30 days from now (no future expiry to extend from).
        assert(captured.captured.isAfter(before.plusDays(29)))
        assert(captured.captured.isBefore(before.plusDays(31)))
    }

    // --- claimDues (member-initiated) ---

    private fun callerMembership(status: MembershipStatus) = membership(status).copy(userId = callerId)

    // Production-shape (root-relative) upload URL — exactly what StorageService returns with an empty base-url.
    private val proofUrl = "/uploads/abc-123.jpg"

    @Test
    fun `claimDues records an sbp claim with a screenshot for a frozen member`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "sbp", proofUrl) } returns 1

        val result = service.claimDues(clubId, callerId, "sbp", proofUrl)

        assertEquals("sbp", result.duesClaimMethod)
        verify(exactly = 1) { membershipRepository.claimDues(m.id, "sbp", proofUrl) }
    }

    @Test
    fun `claimDues notifies the club organizer with the member name`() {
        val m = callerMembership(MembershipStatus.frozen)
        val clubOwnerId = UUID.randomUUID()
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 1
        val club = mockk<com.clubs.club.Club>(relaxed = true)
        every { club.ownerId } returns clubOwnerId
        every { club.name } returns "Бег по утрам"
        every { clubRepository.findById(clubId) } returns club
        val organizer = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 99L }
        every { userRepository.findById(clubOwnerId) } returns organizer
        val member = mockk<UsersRecord>(relaxed = true) {
            every { firstName } returns "Иван"
            every { lastName } returns null
        }
        every { userRepository.findById(callerId) } returns member

        service.claimDues(clubId, callerId, "cash", null)

        verify(exactly = 1) { notificationService.sendDuesClaimedDM(99L, "Иван", "Бег по утрам", "cash") }
    }

    @Test
    fun `claimDues survives a notification failure (best-effort)`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 1
        every { clubRepository.findById(clubId) } throws RuntimeException("db down")

        // The claim is already committed; a DM lookup failure must not abort it.
        val result = service.claimDues(clubId, callerId, "cash", null)

        assertEquals("cash", result.duesClaimMethod)
    }

    // --- removeMember (organizer kick) ---

    @Test
    fun `removeMember cancels an active member and DMs the reason`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)
        every { membershipRepository.remove(any()) } returns 1
        val club = mockk<com.clubs.club.Club>(relaxed = true)
        every { club.name } returns "Бег по утрам"
        every { club.ownerId } returns callerId
        every { clubRepository.findById(clubId) } returns club
        val member = mockk<UsersRecord>(relaxed = true) { every { telegramId } returns 55L }
        every { userRepository.findById(targetUserId) } returns member

        val result = service.removeMember(clubId, targetUserId, callerId, "нарушение правил клуба")

        assertEquals("cancelled", result.status)
        verify(exactly = 1) { membershipRepository.remove(any()) }
        // Orphan-application cleanup so the removed member can re-apply cleanly.
        verify(exactly = 1) { applicationRepository.deleteActiveByUserAndClub(targetUserId, clubId) }
        verify(exactly = 1) { notificationService.sendDirectMessage(55L, match { it.contains("нарушение правил клуба") }) }
    }

    @Test
    fun `removeMember rejects a reason shorter than 5 chars`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)
        assertThrows<ValidationException> { service.removeMember(clubId, targetUserId, callerId, " ок ") }
        verify(exactly = 0) { membershipRepository.remove(any()) }
    }

    @Test
    fun `removeMember rejects an already-cancelled member`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.cancelled)
        assertThrows<ValidationException> { service.removeMember(clubId, targetUserId, callerId, "нарушение правил") }
        verify(exactly = 0) { membershipRepository.remove(any()) }
    }

    @Test
    fun `removeMember refuses to remove the organizer`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active, MembershipRole.organizer)
        assertThrows<ValidationException> { service.removeMember(clubId, targetUserId, callerId, "нарушение правил") }
        verify(exactly = 0) { membershipRepository.remove(any()) }
    }

    @Test
    fun `removeMember освобождает подтверждённые брони и продвигает очередь БЕЗ штрафов`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)
        every { membershipRepository.remove(any()) } returns 1
        val eventId = UUID.randomUUID()
        val promotedUserId = UUID.randomUUID()
        every { eventResponseRepository.findConfirmedActiveEventObligations(targetUserId, clubId) } returns
            listOf(com.clubs.event.EventObligation(eventId, OffsetDateTime.now().plusDays(1)))
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns promotedUserId

        service.removeMember(clubId, targetUserId, callerId, "нарушение правил клуба")

        // Слот сериализован локом, ответы удалены, складчины очищены, очередь продвинута
        verify { eventResponseRepository.lockEventSlots(eventId) }
        verify { eventResponseRepository.deleteByUserAndClubAndActiveEvents(targetUserId, clubId) }
        verify { skladchinaRepository.deleteParticipantFromActiveSkladchinasInClub(targetUserId, clubId) }
        verify { eventPublisher.publishEvent(com.clubs.event.WaitlistPromotedEvent(eventId, promotedUserId)) }
        verify { eventPublisher.publishEvent(com.clubs.event.EventRosterChangedEvent(eventId)) }
    }

    @Test
    fun `removeMember без очереди — слот просто освобождается, промоут-событие не летит`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)
        every { membershipRepository.remove(any()) } returns 1
        val eventId = UUID.randomUUID()
        every { eventResponseRepository.findConfirmedActiveEventObligations(targetUserId, clubId) } returns
            listOf(com.clubs.event.EventObligation(eventId, OffsetDateTime.now().plusDays(1)))
        every { eventResponseRepository.promoteFirstWaitlisted(eventId) } returns null

        service.removeMember(clubId, targetUserId, callerId, "нарушение правил клуба")

        verify { eventPublisher.publishEvent(com.clubs.event.EventRosterChangedEvent(eventId)) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType(com.clubs.event.WaitlistPromotedEvent::class)) }
    }

    @Test
    fun `claimDues rejects an sbp claim without a screenshot`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    // --- claimDues из active: раннее продление (membership-lifecycle.md §7, окно T-3 дня) ---

    @Test
    fun `claimDues accepts a renewal claim from an active member inside the T-3 window`() {
        val m = callerMembership(MembershipStatus.active)
            .copy(subscriptionExpiresAt = OffsetDateTime.now().plusDays(2))
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 1

        val result = service.claimDues(clubId, callerId, "cash", null)

        assertEquals("cash", result.duesClaimMethod)
        verify(exactly = 1) { membershipRepository.claimDues(m.id, "cash", null) }
    }

    @Test
    fun `claimDues rejects a renewal claim from an active member outside the T-3 window`() {
        // За месяц вперёд заявить «оплатил» нельзя — орг не сможет осмысленно проверить перевод.
        val m = callerMembership(MembershipStatus.active)
            .copy(subscriptionExpiresAt = OffsetDateTime.now().plusDays(10))
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "cash", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues rejects an active member without a paid subscription window`() {
        // Бесплатное членство (subscription_expires_at IS NULL) — продлевать нечего.
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns
            callerMembership(MembershipStatus.active)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "cash", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues rejects an sbp claim whose proof is not an uploaded image URL`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        // javascript:, an external host, and even an external host with an /uploads/ path must all be
        // rejected — the proof must be OUR own upload (root-relative in prod).
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "javascript:alert(1)") }
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "https://evil.example.com/x.png") }
        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "sbp", "https://evil.example.com/uploads/x.png") }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues records a cash claim without a screenshot (proof ignored)`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 1

        // Even if a client sends a URL for cash, the service stores null.
        val result = service.claimDues(clubId, callerId, "cash", "https://cdn/ignored.jpg")

        assertEquals("cash", result.duesClaimMethod)
        verify(exactly = 1) { membershipRepository.claimDues(m.id, "cash", null) }
    }

    @Test
    fun `claimDues rejects an unknown method`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.frozen)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "crypto", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues rejects a non-frozen member`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns callerMembership(MembershipStatus.active)

        assertThrows<ValidationException> { service.claimDues(clubId, callerId, "cash", null) }
        verify(exactly = 0) { membershipRepository.claimDues(any(), any(), any()) }
    }

    @Test
    fun `claimDues throws NotFound when the caller is not a member`() {
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns null

        assertThrows<NotFoundException> { service.claimDues(clubId, callerId, "cash", null) }
    }

    @Test
    fun `claimDues throws Conflict when the guarded update affects 0 rows`() {
        val m = callerMembership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(callerId, clubId) } returns m
        every { membershipRepository.claimDues(m.id, "cash", null) } returns 0

        assertThrows<ConflictException> { service.claimDues(clubId, callerId, "cash", null) }
    }

    // --- rejectMember (B+C: reject paid join + refund offline) ---

    @Test
    fun `rejectMember cancels a frozen member`() {
        val m = membership(MembershipStatus.frozen)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m

        val result = service.rejectMember(clubId, targetUserId, callerId, reason = "не профиль клуба")

        assertEquals("cancelled", result.status)
        verify(exactly = 1) { membershipRepository.cancel(m.id) }
    }

    @Test
    fun `rejectMember rejects an already-admitted (active) member`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns membership(MembershipStatus.active)

        assertThrows<ValidationException> { service.rejectMember(clubId, targetUserId, callerId, null) }
        verify(exactly = 0) { membershipRepository.cancel(any()) }
    }

    @Test
    fun `rejectMember rejects managing the organizer`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            membership(MembershipStatus.frozen, role = MembershipRole.organizer)

        assertThrows<ValidationException> { service.rejectMember(clubId, targetUserId, callerId, null) }
        verify(exactly = 0) { membershipRepository.cancel(any()) }
    }

    @Test
    fun `rejectMember throws NotFound when the member does not exist`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns null

        assertThrows<NotFoundException> { service.rejectMember(clubId, targetUserId, callerId, null) }
    }

    // --- unmarkDues ---

    @Test
    fun `unmarkDues clears the dues record without changing status`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.unmarkDues(m.id) } returns 1

        val result = service.unmarkDues(clubId, targetUserId, callerId)

        assertEquals("active", result.status)
        verify(exactly = 1) { membershipRepository.unmarkDues(m.id) }
    }

    // --- target-матрица (co-organizers): вызывающий со-орг, клуб принадлежит другому ---

    @Test
    fun `co-org caller can freeze a plain member`() {
        val m = membership(MembershipStatus.active)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 1
        // Клуб принадлежит не вызывающему — caller прошёл менеджерский гейт как со-орг.
        every { clubRepository.findById(clubId) } returns club(ownerId = UUID.randomUUID())

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
    }

    @Test
    fun `co-org caller cannot freeze another co-organizer (403)`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            membership(MembershipStatus.active, role = MembershipRole.co_organizer)
        every { clubRepository.findById(clubId) } returns club(ownerId = UUID.randomUUID())

        assertThrows<ForbiddenException> { service.freezeAccess(clubId, targetUserId, callerId) }
        verify(exactly = 0) { membershipRepository.freezeAccess(any()) }
    }

    @Test
    fun `co-org caller cannot manage the owner (403)`() {
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns
            membership(MembershipStatus.active, role = MembershipRole.organizer)
        every { clubRepository.findById(clubId) } returns club(ownerId = UUID.randomUUID())

        assertThrows<ForbiddenException> { service.removeMember(clubId, targetUserId, callerId, "за нарушение") }
        verify(exactly = 0) { membershipRepository.remove(any()) }
    }

    @Test
    fun `owner can freeze a co-organizer`() {
        val m = membership(MembershipStatus.active, role = MembershipRole.co_organizer)
        every { membershipRepository.findByUserAndClub(targetUserId, clubId) } returns m
        every { membershipRepository.freezeAccess(m.id) } returns 1
        // setUp: клуб принадлежит callerId (владелец).

        val result = service.freezeAccess(clubId, targetUserId, callerId)

        assertEquals("frozen", result.status)
    }
}
