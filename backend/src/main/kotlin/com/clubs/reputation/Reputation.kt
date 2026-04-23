package com.clubs.reputation

import com.clubs.generated.jooq.enums.AttendanceStatus
import com.clubs.generated.jooq.enums.FinalStatus
import com.clubs.generated.jooq.enums.Stage_1Vote
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class Reputation(
    val userId: UUID,
    val clubId: UUID,
    val reliabilityIndex: Int,
    val promiseFulfillmentPct: BigDecimal,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val spontaneityCount: Int,
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

data class FinalizedEventRef(
    val eventId: UUID,
    val clubId: UUID
)

data class ResponseForReputation(
    val userId: UUID,
    val stage1Vote: Stage_1Vote?,
    val finalStatus: FinalStatus?,
    val attendance: AttendanceStatus?
)
