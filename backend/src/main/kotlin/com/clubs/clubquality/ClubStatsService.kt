package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Owner-only club statistics. Ownership is enforced at the controller by `@RequiresOrganizer`
 * (the aspect rejects a missing club with 404 and a non-owner with 403 before this runs), so the
 * null-guard here is defensive — it keeps a consistent 404 contract if ever called directly.
 */
@Service
class ClubStatsService(
    private val clubStatsRepository: ClubStatsRepository,
    private val clubStatsMapper: ClubStatsMapper,
) {

    @Transactional(readOnly = true)
    fun getClubStats(clubId: UUID): ClubStatsDto {
        val stats = clubStatsRepository.findClubStats(clubId)
            ?: throw NotFoundException("Club not found")
        return clubStatsMapper.toDto(stats)
    }

    /** Win-back roster for the «Верните N ушедших» drill-down. Owner-gated at the controller. */
    @Transactional(readOnly = true)
    fun getChurnedMembers(clubId: UUID): List<ChurnedMemberDto> =
        clubStatsRepository.findChurnedMembers(clubId).map(clubStatsMapper::toChurnedDto)
}
