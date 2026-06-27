package com.clubs.club

import com.clubs.common.exception.NotFoundException
import com.clubs.membership.MembershipRepository
import com.clubs.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Builds the organizer trust card shown on the dues-payment sheet (de-Stars: money goes organizer-direct,
 * so the member needs to know who they're paying). Account-focused signals only; the frontend applies the
 * display thresholds so a fresh account shows just identity + «недавно» (never zeros).
 *
 * Read-only and JWT-only at the controller (others-visible, like the club-quality facts) — the organizer
 * is the public host of the club; no ownership gate.
 */
@Service
class OrganizerCardService(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val userRepository: UserRepository
) {

    @Transactional(readOnly = true)
    fun getOrganizerCard(clubId: UUID): OrganizerCardDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val owner = userRepository.findById(club.ownerId) ?: throw NotFoundException("Organizer not found")
        val ownedClubIds = clubRepository.findIdsByOwnerId(club.ownerId)
        return OrganizerCardDto(
            firstName = owner.firstName,
            lastName = owner.lastName,
            username = owner.telegramUsername,
            avatarUrl = owner.avatarUrl,
            onPlatformSince = owner.createdAt!!,
            clubsCount = ownedClubIds.size,
            trustedMembers = membershipRepository.countActiveNonOrganizerMembersInClubs(ownedClubIds)
        )
    }
}
