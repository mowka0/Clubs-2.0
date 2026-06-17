package com.clubs.clubquality

import org.springframework.stereotype.Component

@Component
class ClubQualityMapper {

    fun toDto(facts: ClubFacts): ClubFactsDto = ClubFactsDto(
        meetingsPerMonth = facts.meetingsPerMonth,
        avgAttendance = facts.avgAttendance,
        coreSize = facts.coreSize,
        ageMonths = facts.ageMonths,
    )
}
