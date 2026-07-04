package com.clubs.clubquality

import org.springframework.stereotype.Component

@Component
class ClubQualityMapper {

    fun toDto(facts: ClubFacts): ClubFactsDto = ClubFactsDto(
        meetingsPerMonth = facts.meetingsPerMonth,
        avgAttendance = facts.avgAttendance,
        coreSize = facts.coreSize,
        ageMonths = facts.ageMonths,
        totalMeetings = facts.totalMeetings,
        successfulSkladchinas = facts.successfulSkladchinas,
    )

    /** [topInCategory] приходит из сервиса (из ранга L3), а не выводится из [facts] — факты карточки
     *  и скрытый ранг это отдельные источники, объединяемые на границе boolean-значения. */
    fun toCardDto(facts: ClubCardFacts, topInCategory: Boolean): ClubCardFactsDto = ClubCardFactsDto(
        clubId = facts.clubId,
        ageDays = facts.ageDays,
        engagementPercent = facts.engagementPercent,
        topInCategory = topInCategory,
    )
}
