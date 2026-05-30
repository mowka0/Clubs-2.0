package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqMembershipRepository(
    private val dsl: DSLContext,
    private val mapper: MembershipMapper
) : MembershipRepository {

    override fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findById(id: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(MEMBERSHIPS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByUserId(userId: UUID): List<Membership> =
        dsl.select(*MEMBERSHIPS.fields())
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period))
                    .and(CLUBS.IS_ACTIVE.eq(true))
            )
            .fetchInto(MembershipsRecord::class.java)
            .map(mapper::toDomain)

    override fun findClubMembersWithUserInfo(clubId: UUID): List<ClubMemberInfo> =
        dsl.select(
            MEMBERSHIPS.USER_ID,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.JOINED_AT,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            DSL.coalesce(USER_CLUB_REPUTATION.RELIABILITY_INDEX, DSL.`val`(100)).`as`("reliability_index"),
            DSL.coalesce(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, DSL.`val`(BigDecimal.ZERO)).`as`("promise_fulfillment_pct")
        )
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId))
            )
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)))
            .orderBy(DSL.field("reliability_index").desc())
            .fetch { r ->
                ClubMemberInfo(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    reliabilityIndex = r.get("reliability_index", Int::class.java) ?: 100,
                    promiseFulfillmentPct = r.get("promise_fulfillment_pct", BigDecimal::class.java) ?: BigDecimal.ZERO
                )
            }

    override fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo> =
        dsl.select(
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            CLUBS.CATEGORY,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.JOINED_AT,
            DSL.coalesce(USER_CLUB_REPUTATION.RELIABILITY_INDEX, DSL.`val`(100)).`as`("reliability_index"),
            DSL.coalesce(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT, DSL.`val`(BigDecimal.ZERO)).`as`("promise_fulfillment_pct"),
            DSL.coalesce(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS, DSL.`val`(0)).`as`("total_confirmations"),
            DSL.coalesce(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES, DSL.`val`(0)).`as`("total_attendances")
        )
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(CLUBS.ID))
            )
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period))
                    .and(CLUBS.IS_ACTIVE.eq(true))
            )
            .orderBy(MEMBERSHIPS.JOINED_AT.desc().nullsLast())
            .fetch { r ->
                UserClubReputationInfo(
                    clubId = r.get(CLUBS.ID)!!,
                    clubName = r.get(CLUBS.NAME)!!,
                    clubAvatarUrl = r.get(CLUBS.AVATAR_URL),
                    category = r.get(CLUBS.CATEGORY)!!,
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT),
                    reliabilityIndex = r.get("reliability_index", Int::class.java) ?: 100,
                    promiseFulfillmentPct = r.get("promise_fulfillment_pct", BigDecimal::class.java) ?: BigDecimal.ZERO,
                    totalConfirmations = r.get("total_confirmations", Int::class.java) ?: 0,
                    totalAttendances = r.get("total_attendances", Int::class.java) ?: 0
                )
            }

    override fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef? =
        dsl.select(MEMBERSHIPS.ID, MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
            .from(MEMBERSHIPS)
            .where(MEMBERSHIPS.USER_ID.eq(userId).and(MEMBERSHIPS.CLUB_ID.eq(clubId)))
            .fetchOne { record ->
                MembershipExpiryRef(
                    id = record.get(MEMBERSHIPS.ID)!!,
                    subscriptionExpiresAt = record.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
                )
            }

    override fun isMember(userId: UUID, clubId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                )
        )

    override fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                        .and(CLUBS.IS_ACTIVE.eq(true))
                )
        )

    override fun countActiveByClubId(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun create(userId: UUID, clubId: UUID): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, now.plusDays(30))
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun createOrganizer(userId: UUID, clubId: UUID): Membership {
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.organizer)
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun cancel(membershipId: UUID) {
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
    }

    override fun activateSubscription(userId: UUID, clubId: UUID, expiresAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, id)
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, expiresAt)
            .execute()
        return id
    }

    override fun renewSubscription(membershipId: UUID, newExpiresAt: OffsetDateTime) {
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, newExpiresAt)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
    }

    override fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        dsl.select(USERS.TELEGRAM_ID, CLUBS.NAME)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(threshold))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
            )
            .fetch { record ->
                ExpiringSubscriptionNotification(
                    telegramId = record.get(USERS.TELEGRAM_ID)!!,
                    clubName = record.get(CLUBS.NAME)!!
                )
            }

    override fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification> =
        dsl.select(USERS.TELEGRAM_ID, CLUBS.NAME)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .fetch { record ->
                ExpiringSubscriptionNotification(
                    telegramId = record.get(USERS.TELEGRAM_ID)!!,
                    clubName = record.get(CLUBS.NAME)!!
                )
            }

    override fun moveActiveToGracePeriod(now: OffsetDateTime): Int =
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.grace_period)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .execute()

    override fun findGracePeriodExpiredGroupedByClub(gracePeriodEnd: OffsetDateTime): List<ClubMembershipExpiredCount> =
        dsl.select(MEMBERSHIPS.CLUB_ID, DSL.count())
            .from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.grace_period)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(gracePeriodEnd))
            )
            .groupBy(MEMBERSHIPS.CLUB_ID)
            .fetch { record ->
                ClubMembershipExpiredCount(
                    clubId = record.value1()!!,
                    count = record.value2()
                )
            }

    override fun moveGracePeriodToExpired(gracePeriodEnd: OffsetDateTime): Int =
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.expired)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.grace_period)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(gracePeriodEnd))
            )
            .execute()

    // Preserves master-branch semantics: ALL rows for the club, regardless of
    // status (cancelled/expired included). NotificationService relied on this
    // for "event created" DMs. Tightening this filter is out of scope for the
    // membership refactor — would change runtime behaviour.
    override fun findMemberTelegramIds(clubId: UUID): List<Long> =
        dsl.select(USERS.TELEGRAM_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId))
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
}
