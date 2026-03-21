package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class MembershipRepository(private val dsl: DSLContext) {

    fun create(userId: UUID, clubId: UUID): MembershipsRecord {
        val now = OffsetDateTime.now()
        return dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, now.plusDays(30))
            .returning()
            .fetchOne()!!
    }

    fun findByUserAndClub(userId: UUID, clubId: UUID): MembershipsRecord? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period))
            )
            .fetchOne()

    fun countActiveByClubId(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetchOne(0, Int::class.java) ?: 0

    fun findByClubId(clubId: UUID): List<MembershipsRecord> =
        dsl.selectFrom(MEMBERSHIPS)
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId))
            .fetch()
}
