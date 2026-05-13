package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqApplicationRepository(
    private val dsl: DSLContext,
    private val mapper: ApplicationMapper
) : ApplicationRepository {

    override fun create(userId: UUID, clubId: UUID, answerText: String?): Application {
        val record = dsl.insertInto(APPLICATIONS)
            .set(APPLICATIONS.USER_ID, userId)
            .set(APPLICATIONS.CLUB_ID, clubId)
            .set(APPLICATIONS.ANSWER_TEXT, answerText)
            .set(APPLICATIONS.STATUS, ApplicationStatus.pending)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun updateStatus(id: UUID, status: ApplicationStatus, reason: String?): Application {
        val record = dsl.update(APPLICATIONS)
            .set(APPLICATIONS.STATUS, status)
            .set(APPLICATIONS.REJECTED_REASON, reason)
            .set(APPLICATIONS.RESOLVED_AT, OffsetDateTime.now())
            .where(APPLICATIONS.ID.eq(id))
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun findById(id: UUID): Application? =
        dsl.selectFrom(APPLICATIONS)
            .where(APPLICATIONS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByClubId(clubId: UUID, status: ApplicationStatus?): List<Application> {
        var condition = APPLICATIONS.CLUB_ID.eq(clubId)
        status?.let { condition = condition.and(APPLICATIONS.STATUS.eq(it)) }
        return dsl.selectFrom(APPLICATIONS)
            .where(condition)
            .orderBy(APPLICATIONS.CREATED_AT.desc())
            .fetch()
            .map(mapper::toDomain)
    }

    override fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Application? =
        dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.CLUB_ID.eq(clubId))
                    .and(APPLICATIONS.STATUS.`in`(ApplicationStatus.pending, ApplicationStatus.approved))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByUserId(userId: UUID): List<Application> =
        dsl.selectFrom(APPLICATIONS)
            .where(APPLICATIONS.USER_ID.eq(userId))
            .orderBy(APPLICATIONS.CREATED_AT.desc())
            .fetch()
            .map(mapper::toDomain)

    override fun countTodayByUser(userId: UUID): Int {
        val startOfDay = OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC)
        return dsl.selectCount().from(APPLICATIONS)
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.CREATED_AT.greaterOrEqual(startOfDay))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun findPendingOlderThan(cutoff: OffsetDateTime): List<Application> =
        dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.STATUS.eq(ApplicationStatus.pending)
                    .and(APPLICATIONS.CREATED_AT.lessOrEqual(cutoff))
            )
            .fetch()
            .map(mapper::toDomain)

    override fun markAutoRejected(cutoff: OffsetDateTime): Int =
        dsl.update(APPLICATIONS)
            .set(APPLICATIONS.STATUS, ApplicationStatus.auto_rejected)
            .set(APPLICATIONS.RESOLVED_AT, OffsetDateTime.now())
            .where(
                APPLICATIONS.STATUS.eq(ApplicationStatus.pending)
                    .and(APPLICATIONS.CREATED_AT.lessOrEqual(cutoff))
            )
            .execute()
}
