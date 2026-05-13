package com.clubs.application

import com.clubs.generated.jooq.tables.records.ApplicationsRecord
import org.springframework.stereotype.Component

@Component
class ApplicationMapper {

    fun toDomain(record: ApplicationsRecord): Application = Application(
        id = record.id!!,
        userId = record.userId,
        clubId = record.clubId,
        answerText = record.answerText,
        status = record.status!!,
        rejectedReason = record.rejectedReason,
        createdAt = record.createdAt!!,
        resolvedAt = record.resolvedAt
    )

    fun toDto(application: Application): ApplicationDto = ApplicationDto(
        id = application.id,
        userId = application.userId,
        clubId = application.clubId,
        status = application.status.literal,
        answerText = application.answerText,
        rejectedReason = application.rejectedReason,
        createdAt = application.createdAt,
        resolvedAt = application.resolvedAt
    )
}
