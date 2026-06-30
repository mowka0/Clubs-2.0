package com.clubs.award

import com.clubs.generated.jooq.tables.records.ClubAwardsRecord
import org.springframework.stereotype.Component

@Component
class AwardMapper {

    fun recordToDomain(record: ClubAwardsRecord): Award = Award(
        id = record.id!!,
        clubId = record.clubId,
        userId = record.userId,
        emoji = record.emoji,
        label = record.label,
        awardedBy = record.awardedBy,
        awardedAt = record.awardedAt!!
    )

    fun toDto(award: Award): AwardDto = AwardDto(
        id = award.id,
        emoji = award.emoji,
        label = award.label
    )

    fun toDto(suggestion: AwardSuggestion): AwardSuggestionDto = AwardSuggestionDto(
        emoji = suggestion.emoji,
        label = suggestion.label
    )
}
