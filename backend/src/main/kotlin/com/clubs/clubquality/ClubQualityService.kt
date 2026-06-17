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

    @Transactional(readOnly = true)
    fun getClubFacts(clubId: UUID): ClubFactsDto {
        val facts = clubQualityRepository.findClubFacts(clubId)
            ?: throw NotFoundException("Club not found")
        return clubQualityMapper.toDto(facts)
    }
}
