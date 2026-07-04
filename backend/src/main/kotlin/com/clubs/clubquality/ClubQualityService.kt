package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ClubQualityService(
    private val clubQualityRepository: ClubQualityRepository,
    private val clubQualityMapper: ClubQualityMapper,
    private val clubRankService: ClubRankService,
) {

    private companion object {
        /**
         * Жёсткий потолок одного батч-вызова. Фронтенд Discovery запрашивает один батч на страницу
         * (≤20 id), так что 50 — щедрый запас; лимит нужен, чтобы ограничить сырой вызов с тысячами id.
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
     * Факты discovery-карточек для страницы клубов. Дедуплицируются и режутся по [MAX_BATCH_SIZE];
     * id без строки клуба просто отсутствуют в результате (без 404 — частичные страницы нормальны).
     */
    @Transactional(readOnly = true)
    fun getClubCardFacts(clubIds: List<UUID>): List<ClubCardFactsDto> {
        val ids = clubIds.distinct().take(MAX_BATCH_SIZE)
        if (ids.isEmpty()) return emptyList()
        val facts = clubQualityRepository.findClubCardFacts(ids)
        val badged = clubRankService.badgedAmong(facts.map { it.clubId })
        return facts.map { clubQualityMapper.toCardDto(it, topInCategory = it.clubId in badged) }
    }
}
