package com.clubs.reputation

import com.clubs.generated.jooq.tables.records.UserClubReputationRecord
import org.springframework.stereotype.Component

@Component
class ReputationMapper {

    fun toDomain(record: UserClubReputationRecord): Reputation = Reputation(
        userId = record.userId!!,
        clubId = record.clubId!!,
        reliabilityIndex = record.reliabilityIndex!!,
        promiseFulfillmentPct = record.promiseFulfillmentPct!!,
        totalConfirmations = record.totalConfirmations!!,
        totalAttendances = record.totalAttendances!!,
        spontaneityCount = record.spontaneityCount!!,
        updatedAt = record.updatedAt!!
    )
}
