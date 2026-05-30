package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.APPLICATIONS
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.jooq.impl.DSL
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

    override fun findPendingByClubIds(clubIds: Collection<UUID>): List<Application> {
        if (clubIds.isEmpty()) return emptyList()
        return dsl.selectFrom(APPLICATIONS)
            .where(
                APPLICATIONS.CLUB_ID.`in`(clubIds)
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.pending))
            )
            .orderBy(APPLICATIONS.CREATED_AT.asc())
            .fetch()
            .map(mapper::toDomain)
    }

    override fun countPendingByClubIds(clubIds: Collection<UUID>): Int {
        if (clubIds.isEmpty()) return 0
        return dsl.selectCount().from(APPLICATIONS)
            .where(
                APPLICATIONS.CLUB_ID.`in`(clubIds)
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.pending))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun findApprovedWithoutMembershipByUserId(userId: UUID): List<Application> {
        // Awaiting-payment surface = approved apps for PAID clubs that haven't
        // yielded an active/grace_period membership yet (Stars invoice unpaid).
        // Free clubs (subscription_price = 0 / null) auto-create membership on
        // approve, so they must NEVER appear here even if data is inconsistent.
        // Single round-trip via JOIN + NOT EXISTS, no N+1.
        val membershipExists = DSL.exists(
            DSL.selectOne()
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.USER_ID.eq(APPLICATIONS.USER_ID)
                        .and(MEMBERSHIPS.CLUB_ID.eq(APPLICATIONS.CLUB_ID))
                        .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period))
                )
        )
        return dsl.select(APPLICATIONS.asterisk()).from(APPLICATIONS)
            .join(CLUBS).on(CLUBS.ID.eq(APPLICATIONS.CLUB_ID))
            .where(
                APPLICATIONS.USER_ID.eq(userId)
                    .and(APPLICATIONS.STATUS.eq(ApplicationStatus.approved))
                    .and(CLUBS.SUBSCRIPTION_PRICE.greaterThan(0))
                    .and(DSL.not(membershipExists))
            )
            .orderBy(APPLICATIONS.RESOLVED_AT.desc().nullsLast())
            .fetchInto(APPLICATIONS)
            .map(mapper::toDomain)
    }

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
