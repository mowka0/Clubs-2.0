package com.clubs.membership

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MembershipService(
    private val membershipRepository: MembershipRepository,
    private val clubRepository: ClubRepository,
    private val dsl: DSLContext
) {

    fun joinOpenClub(clubId: UUID, userId: UUID): MembershipDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")

        if (club.accessType != AccessType.`open`) {
            throw ValidationException("Club is not open for joining")
        }

        val existing = membershipRepository.findByUserAndClub(userId, clubId)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(clubId)
        if (activeCount >= club.memberLimit) throw ValidationException("Club is full")

        val membership = membershipRepository.create(userId, clubId)

        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_COUNT, (club.memberCount ?: 0) + 1)
            .where(CLUBS.ID.eq(clubId))
            .execute()

        return membership.toDto()
    }

    fun joinByInviteCode(code: String, userId: UUID): MembershipDto {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")

        val existing = membershipRepository.findByUserAndClub(userId, club.id!!)
        if (existing != null) throw ConflictException("Already a member")

        val activeCount = membershipRepository.countActiveByClubId(club.id!!)
        if (activeCount >= (club.memberLimit ?: 0)) throw ValidationException("Club is full")

        val membership = membershipRepository.create(userId, club.id!!)

        dsl.update(CLUBS)
            .set(CLUBS.MEMBER_COUNT, (club.memberCount ?: 0) + 1)
            .where(CLUBS.ID.eq(club.id))
            .execute()

        return membership.toDto()
    }
}

fun MembershipsRecord.toDto() = MembershipDto(
    id = id!!,
    userId = userId,
    clubId = clubId,
    status = status?.literal ?: "active",
    role = role?.literal ?: "member",
    joinedAt = joinedAt,
    subscriptionExpiresAt = subscriptionExpiresAt
)
