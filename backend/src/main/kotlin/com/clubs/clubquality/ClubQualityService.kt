package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ClubQualityService(
    private val clubQualityRepository: ClubQualityRepository,
    private val clubQualityMapper: ClubQualityMapper,
) {

    private companion object {
        /**
         * Hard cap on one batch call. The Discovery frontend requests one batch per page (≤20 ids),
         * so 50 is generous headroom; the cap exists to bound a raw caller passing thousands of ids.
         */
        const val MAX_BATCH_SIZE = 50
    }

    @Transactional(readOnly = true)
    fun getClubFacts(clubId: UUID): ClubFactsDto {
        val facts = clubQualityRepository.findClubFacts(clubId)
            ?: throw NotFoundException("Club not found")
        return clubQualityMapper.toDto(facts)
    }

    /**
     * Discovery-card facts for a page of clubs. Deduped and capped at [MAX_BATCH_SIZE]; ids without a
     * club row are simply absent from the result (no 404 — partial pages are normal).
     */
    @Transactional(readOnly = true)
    fun getClubCardFacts(clubIds: List<UUID>): List<ClubCardFactsDto> {
        val ids = clubIds.distinct().take(MAX_BATCH_SIZE)
        if (ids.isEmpty()) return emptyList()
        return clubQualityRepository.findClubCardFacts(ids).map(clubQualityMapper::toCardDto)
    }
}
