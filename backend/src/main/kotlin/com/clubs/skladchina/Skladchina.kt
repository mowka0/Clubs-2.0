package com.clubs.skladchina

import com.clubs.generated.jooq.enums.SkladchinaMode
import com.clubs.generated.jooq.enums.SkladchinaStatus
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import java.time.OffsetDateTime
import java.util.UUID

data class Skladchina(
    val id: UUID,
    val clubId: UUID,
    val creatorId: UUID,

    val title: String,
    val description: String?,
    val rules: String?,
    val photoUrl: String?,

    val template: SkladchinaTemplate,
    val paymentMode: SkladchinaMode,
    val totalGoalKopecks: Long?,
    val paymentLink: String,
    val paymentMethodNote: String?,
    val eventId: UUID?,                 // split_bill: the source event whose bill is split

    val deadline: OffsetDateTime,
    val affectsReputation: Boolean,

    val status: SkladchinaStatus,
    val closedAt: OffsetDateTime?,
    val closedBy: UUID?,

    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
