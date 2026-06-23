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

    /** [topInCategory] is supplied by the service (from the L3 rank), not derived from [facts] — the
     *  card facts and the hidden rank are separate sources merged at the boolean boundary. */
    fun toCardDto(facts: ClubCardFacts, topInCategory: Boolean): ClubCardFactsDto = ClubCardFactsDto(
        clubId = facts.clubId,
        ageDays = facts.ageDays,
        engagementPercent = facts.engagementPercent,
        topInCategory = topInCategory,
    )
}
