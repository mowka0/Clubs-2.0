package com.clubs.club

import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.generated.jooq.tables.records.ClubsRecord
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ClubService(private val clubRepository: ClubRepository) {

    fun createClub(request: CreateClubRequest, ownerId: UUID): ClubDetailDto {
        validateCategory(request.category)
        validateAccessType(request.accessType)

        val count = clubRepository.countByOwnerId(ownerId)
        if (count >= 10) throw ConflictException("Maximum 10 clubs per organizer")

        val club = clubRepository.create(request, ownerId)
        return club.toDto()
    }

    fun getClub(id: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        return club.toDto()
    }

    fun updateClub(id: UUID, request: UpdateClubRequest, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can update it")
        val updated = clubRepository.update(id, request) ?: throw NotFoundException("Club not found after update")
        return updated.toDto()
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
