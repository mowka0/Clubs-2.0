package com.clubs.club

import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.ClubsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ClubService(
    private val clubRepository: ClubRepository,
    private val dsl: DSLContext
) {

    private val log = LoggerFactory.getLogger(ClubService::class.java)

    fun getClubs(filters: ClubFilterParams): PageResponse<ClubListItemDto> {
        filters.category?.let { validateCategory(it) }
        filters.accessType?.let { validateAccessType(it) }
        if (filters.minPrice != null && filters.maxPrice != null && filters.minPrice > filters.maxPrice) {
            throw ValidationException("minPrice must not be greater than maxPrice")
        }
        return clubRepository.findAll(filters)
    }

    fun createClub(request: CreateClubRequest, ownerId: UUID): ClubDetailDto {
        validateCategory(request.category)
        validateAccessType(request.accessType)

        val count = clubRepository.countByOwnerId(ownerId)
        if (count >= 10) throw ConflictException("Maximum 10 clubs per organizer")

        val inviteCode = if (request.accessType == "private") UUID.randomUUID().toString().replace("-", "").take(16) else null
        val club = clubRepository.create(request, ownerId, inviteCode)
        log.info("Club created: id={} name='{}' category={} accessType={} ownerId={}", club.id, club.name, request.category, request.accessType, ownerId)

        // Auto-create organizer membership for the owner
        dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.USER_ID, ownerId)
            .set(MEMBERSHIPS.CLUB_ID, club.id)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.organizer)
            .execute()

        return club.toDto()
    }

    fun getClubByInviteCode(code: String): ClubDetailDto {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        return club.toDto()
    }

    fun regenerateInviteLink(clubId: UUID, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can regenerate invite link")
        val newCode = UUID.randomUUID().toString().replace("-", "").take(16)
        val updated = clubRepository.updateInviteCode(clubId, newCode) ?: throw NotFoundException("Club not found")
        log.info("Invite link regenerated: clubId={} userId={}", clubId, userId)
        return updated.toDto()
    }

    fun getClub(id: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        return club.toDto()
    }

    fun linkTelegramGroup(clubId: UUID, telegramGroupId: Long, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can link a Telegram group")
        dsl.update(CLUBS)
            .set(DSL.field("telegram_group_id"), telegramGroupId)
            .where(CLUBS.ID.eq(clubId))
            .execute()
        log.info("Telegram group {} linked to club {}: userId={}", telegramGroupId, clubId, userId)
        return clubRepository.findById(clubId)!!.toDto()
    }

    fun updateClub(id: UUID, request: UpdateClubRequest, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can update it")
        val updated = clubRepository.update(id, request) ?: throw NotFoundException("Club not found after update")
        log.info("Club updated: id={} userId={}", id, userId)
        return updated.toDto()
    }

    fun deleteClub(id: UUID, userId: UUID) {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can delete it")
        clubRepository.softDelete(id)
        log.info("Club soft-deleted: id={} userId={}", id, userId)
    }

    private fun validateCategory(category: String) {
        try {
            ClubCategory.valueOf(category)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid category: $category")
        }
    }

    private fun validateAccessType(accessType: String) {
        try {
            AccessType.valueOf(accessType)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid access type: $accessType")
        }
    }
}

fun ClubsRecord.toDto() = ClubDetailDto(
    id = id!!,
    ownerId = ownerId,
    name = name,
    description = description,
    category = category.literal,
    accessType = accessType?.literal ?: "open",
    city = city,
    district = district,
    memberLimit = memberLimit,
    subscriptionPrice = subscriptionPrice ?: 0,
    avatarUrl = avatarUrl,
    rules = rules,
    applicationQuestion = applicationQuestion,
    inviteLink = inviteLink,
    memberCount = memberCount ?: 0,
    activityRating = activityRating ?: 0,
    isActive = isActive ?: true
)
