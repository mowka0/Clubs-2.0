package com.clubs.skladchina

import com.clubs.club.ClubRepository
import com.clubs.common.exception.ForbiddenException
import com.clubs.common.exception.NotFoundException
import com.clubs.generated.jooq.enums.SkladchinaParticipantStatus
import com.clubs.skladchina.template.DeclinePolicy
import com.clubs.skladchina.template.SkladchinaTemplateRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read side of the skladchina engine: detail + club-active list, with access checks and DTO mapping.
 * Has no mutation, so every other service depends on it for the response DTO they return (a leaf in
 * the dependency graph). Split out of the former god-`SkladchinaService` by responsibility.
 */
@Service
class SkladchinaQueryService(
    private val skladchinaRepository: SkladchinaRepository,
    private val clubRepository: ClubRepository,
    private val mapper: SkladchinaMapper,
    private val templateRegistry: SkladchinaTemplateRegistry
) {

    @Transactional(readOnly = true)
    fun getClubActiveSkladchinas(clubId: UUID, callerId: UUID): List<MySkladchinaListItemDto> {
        val club = clubRepository.findById(clubId) ?: throw NotFoundException("Club not found")
        val skladchinas = skladchinaRepository.findActiveByClub(clubId)
        return skladchinas.map { s ->
            val collected = skladchinaRepository.sumCollectedKopecks(s.id)
            val totalParticipants = skladchinaRepository.countParticipants(s.id)
            val paid = skladchinaRepository.countParticipantsByStatus(s.id, SkladchinaParticipantStatus.paid)
            val callerParticipant = skladchinaRepository.findParticipant(s.id, callerId)
            mapper.toMyFeedItemDto(
                MySkladchinaFeedItem(
                    skladchina = s,
                    clubName = club.name,
                    clubAvatarUrl = club.avatarUrl,
                    myStatus = callerParticipant?.status,
                    collectedKopecks = collected,
                    participantCount = totalParticipants,
                    paidCount = paid
                ),
                callerId
            )
        }
    }

    @Transactional(readOnly = true)
    fun getDetail(skladchinaId: UUID, callerId: UUID): SkladchinaDetailDto {
        val skladchina = skladchinaRepository.findById(skladchinaId)
            ?: throw NotFoundException("Skladchina not found")
        val club = clubRepository.findById(skladchina.clubId)
            ?: throw NotFoundException("Club not found")

        // Access: creator OR active participant
        val isCreator = skladchina.creatorId == callerId
        val callerParticipant = skladchinaRepository.findParticipant(skladchinaId, callerId)
        if (!isCreator && callerParticipant == null) {
            throw ForbiddenException("Not allowed to view this skladchina")
        }

        val participants = skladchinaRepository.findParticipantsWithInfo(skladchinaId)
        val collected = skladchinaRepository.sumCollectedKopecks(skladchinaId)
        val declineRequiresApproval =
            templateRegistry.forType(skladchina.template).declinePolicy == DeclinePolicy.REQUIRES_APPROVAL
        return mapper.toDetailDto(
            skladchina, club.name, club.avatarUrl, callerId, participants, collected, declineRequiresApproval
        )
    }
}
