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

    fun toCardDto(facts: ClubCardFacts): ClubCardFactsDto = ClubCardFactsDto(
        clubId = facts.clubId,
        ageDays = facts.ageDays,
        engagementPercent = facts.engagementPercent,
    )
}
