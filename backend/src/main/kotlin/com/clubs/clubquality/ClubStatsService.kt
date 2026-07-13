package com.clubs.clubquality

import com.clubs.common.exception.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Статистика клуба для менеджера (владелец или активный со-организатор). Право проверяется на уровне
 * контроллера через `@RequiresCapability(VIEW_STATS)` (аспект отклоняет отсутствующий клуб с 404 и
 * не-менеджера с 403 ещё до вызова этого кода), поэтому проверка на null здесь защитная — она
 * сохраняет единообразный контракт 404, если этот код когда-нибудь вызовут напрямую.
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

    /** Список для возврата участников — drill-down «Верните N ушедших». Доступ ограничен владельцем на контроллере. */
    @Transactional(readOnly = true)
    fun getChurnedMembers(clubId: UUID): List<ChurnedMemberDto> =
        clubStatsRepository.findChurnedMembers(clubId).map(clubStatsMapper::toChurnedDto)
}
