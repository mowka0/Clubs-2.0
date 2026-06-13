package com.clubs.club

import com.clubs.common.dto.PageResponse
import com.clubs.common.exception.ConflictException
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.common.exception.ValidationException
import com.clubs.event.EventRepository
import com.clubs.generated.jooq.enums.AccessType
import com.clubs.generated.jooq.enums.ClubCategory
import com.clubs.membership.MembershipRepository
import com.clubs.skladchina.SkladchinaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val MAX_CLUBS_PER_ORGANIZER = 10
private const val INVITE_CODE_LENGTH = 16

@Service
class ClubService(
    private val clubRepository: ClubRepository,
    private val membershipRepository: MembershipRepository,
    private val eventRepository: EventRepository,
    private val skladchinaRepository: SkladchinaRepository,
    private val mapper: ClubMapper
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

    @Transactional
    fun createClub(request: CreateClubRequest, ownerId: UUID): ClubDetailDto {
        validateCategory(request.category)
        validateAccessType(request.accessType)

        val count = clubRepository.countByOwnerId(ownerId)
        if (count >= MAX_CLUBS_PER_ORGANIZER) throw ConflictException("Maximum $MAX_CLUBS_PER_ORGANIZER clubs per organizer")

        val inviteCode = if (request.accessType == "private") generateInviteCode() else null
        val club = clubRepository.create(request, ownerId, inviteCode)
        log.info("Club created: id={} name='{}' category={} accessType={} ownerId={}", club.id, club.name, request.category, request.accessType, ownerId)

        // Auto-create organizer membership for the owner.
        // Wrapped in the same @Transactional scope as clubRepository.create() above —
        // if this INSERT fails the club row rolls back, preventing orphaned clubs
        // without an organizer membership.
        membershipRepository.createOrganizer(ownerId, club.id)

        return mapper.toDetailDto(club)
    }

    fun getClubByInviteCode(code: String): ClubDetailDto {
        val club = clubRepository.findByInviteCode(code) ?: throw NotFoundException("Invite link not found")
        return mapper.toDetailDto(club)
    }

    @Transactional
    fun regenerateInviteLink(clubId: UUID, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can regenerate invite link")
        val newCode = generateInviteCode()
        val updated = clubRepository.updateInviteCode(clubId, newCode) ?: throw NotFoundException("Club not found")
        log.info("Invite link regenerated: clubId={} userId={}", clubId, userId)
        return mapper.toDetailDto(updated)
    }

    fun getClub(id: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        return mapper.toDetailDto(club)
    }

    @Transactional
    fun linkTelegramGroup(clubId: UUID, telegramGroupId: Long, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can link a Telegram group")
        clubRepository.linkTelegramGroup(clubId, telegramGroupId)
        log.info("Telegram group {} linked to club {}: userId={}", telegramGroupId, clubId, userId)
        val updated = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found after update")
        return mapper.toDetailDto(updated)
    }

    @Transactional
    fun updateClub(id: UUID, request: UpdateClubRequest, userId: UUID): ClubDetailDto {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can update it")
        val updated = clubRepository.update(id, request) ?: throw NotFoundException("Club not found after update")
        log.info("Club updated: id={} userId={}", id, userId)
        return mapper.toDetailDto(updated)
    }

    @Transactional
    fun deleteClub(id: UUID, userId: UUID) {
        val club = clubRepository.findById(id) ?: throw NotFoundException("Club not found")
        if (club.ownerId != userId) throw ForbiddenException("Only the club owner can delete it")

        // Cascade: a soft-deleted club must not leave live events/skladchinas behind. Otherwise
        // schedulers keep processing them (phantom "mark attendance" DMs, late expiry penalties)
        // and their detail pages 404 on the now-hidden club. We cancel non-finalized events and
        // active skladchinas via the repositories directly — NOT through their Services — so the
        // cascade never touches reputation (finalized events keep their ledger; pending skladchina
        // participants are released, not penalized). See docs/backlog/orphan-memberships-cleanup.md.
        // Memberships/applications need no action here: "my clubs" already filter clubs.is_active.
        val cancelledEvents = eventRepository.cancelActiveEventsByClub(id)
        val cancelledSkladchinas = skladchinaRepository.cancelActiveByClub(id)

        clubRepository.softDelete(id)
        log.info(
            "Club soft-deleted: id={} userId={} cancelledEvents={} cancelledSkladchinas={}",
            id, userId, cancelledEvents, cancelledSkladchinas
        )
    }

    private fun generateInviteCode(): String =
        UUID.randomUUID().toString().replace("-", "").take(INVITE_CODE_LENGTH)

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
