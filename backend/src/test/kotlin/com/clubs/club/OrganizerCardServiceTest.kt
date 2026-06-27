package com.clubs.club

import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

class OrganizerCardServiceTest {

    private val clubRepository = mockk<ClubRepository>()
    private val membershipRepository = mockk<MembershipRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val service = OrganizerCardService(clubRepository, membershipRepository, userRepository)

    private val clubId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()
    private val since = OffsetDateTime.now().minusYears(1)

    private fun ownerRecord(): UsersRecord = mockk(relaxed = true) {
        every { firstName } returns "Иван"
        every { lastName } returns "Петров"
        every { telegramUsername } returns "ivan_p"
        every { avatarUrl } returns null
        every { createdAt } returns since
    }

    @Test
    fun `getOrganizerCard returns owner identity + aggregates`() {
        every { clubRepository.findById(clubId) } returns mockk { every { ownerId } returns this@OrganizerCardServiceTest.ownerId }
        every { userRepository.findById(ownerId) } returns ownerRecord()
        every { clubRepository.findIdsByOwnerId(ownerId) } returns listOf(clubId, UUID.randomUUID(), UUID.randomUUID())
        every { membershipRepository.countActiveNonOrganizerMembersInClubs(any()) } returns 40

        val card = service.getOrganizerCard(clubId)

        assertEquals("Иван", card.firstName)
        assertEquals("ivan_p", card.username)
        assertEquals(since, card.onPlatformSince)
        assertEquals(3, card.clubsCount)
        assertEquals(40, card.trustedMembers)
    }

    @Test
    fun `getOrganizerCard throws NotFound when club is missing`() {
        every { clubRepository.findById(clubId) } returns null
        assertThrows<NotFoundException> { service.getOrganizerCard(clubId) }
    }

    @Test
    fun `getOrganizerCard throws NotFound when owner is missing`() {
        every { clubRepository.findById(clubId) } returns mockk { every { ownerId } returns this@OrganizerCardServiceTest.ownerId }
        every { userRepository.findById(ownerId) } returns null
        assertThrows<NotFoundException> { service.getOrganizerCard(clubId) }
    }
}
