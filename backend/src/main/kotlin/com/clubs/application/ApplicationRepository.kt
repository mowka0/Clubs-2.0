package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.tables.records.ApplicationsRecord
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class ApplicationRepository(private val dsl: DSLContext) {

    fun create(userId: UUID, clubId: UUID, answerText: String?): ApplicationsRecord =
        dsl.insertInto(APPLICATIONS)
            .set(APPLICATIONS.USER_ID, userId)
            .set(APPLICATIONS.CLUB_ID, clubId)
            .set(APPLICATIONS.ANSWER_TEXT, answerText)
            .set(APPLICATIONS.STATUS, ApplicationStatus.pending)
            .returning()
            .fetchOne()!!

    fun findById(id: UUID): ApplicationsRecord? =
        dsl.selectFrom(APPLICATIONS)
            .where(APPLICATIONS.ID.eq(id))
            .fetchOne()

    fun findByClubId(clubId: UUID, status: ApplicationStatus?): List<ApplicationsRecord> {
        var condition = APPLICATIONS.CLUB_ID.eq(clubId)
        status?.let { condition = condition.and(APPLICATIONS.STATUS.eq(it)) }
        return dsl.selectFrom(APPLICATIONS)
            .where(condition)
            .orderBy(APPLICATIONS.CREATED_AT.desc())
            .fetch()
    }

    fun findPendingByUserAndClub(userId: UUID, clubId: UUID): ApplicationsRecord? =
        dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.CLUB_ID.eq(clubId))
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.pending))
            )
            .fetchOne()

    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): ApplicationsRecord? =
        dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.CLUB_ID.eq(clubId))
                    .and(APPLICATIONS.STATUS.`in`(ApplicationStatus.pending, ApplicationStatus.approved))
            )
            .fetchOne()

    fun countTodayByUser(userId: UUID): Int {
        val startOfDay = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC)
        return dsl.selectCount().from(APPLICATIONS)
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.CREATED_AT.greaterOrEqual(startOfDay))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    fun updateStatus(id: UUID, status: ApplicationStatus, reason: String? = null): ApplicationsRecord =
        dsl.update(APPLICATIONS)
            .set(APPLICATIONS.STATUS, status)
            .set(APPLICATIONS.REJECTED_REASON, reason)
            .set(APPLICATIONS.RESOLVED_AT, OffsetDateTime.now())
            .where(APPLICATIONS.ID.eq(id))
            .returning()
            .fetchOne()!!

    fun findByUserId(userId: UUID): List<ApplicationsRecord> =
        dsl.selectFrom(APPLICATIONS)
            .where(APPLICATIONS.USER_ID.eq(userId))
            .orderBy(APPLICATIONS.CREATED_AT.desc())
            .fetch()
}

fun ApplicationsRecord.toDto() = ApplicationDto(
    id = id!!,
    userId = userId!!,
    clubId = clubId!!,
    status = status?.literal ?: "pending",
    answerText = answerText,
    rejectedReason = rejectedReason,
    createdAt = createdAt,
    resolvedAt = resolvedAt
)
