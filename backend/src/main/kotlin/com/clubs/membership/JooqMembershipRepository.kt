package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipRole
import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.records.MembershipsRecord
import com.clubs.generated.jooq.tables.references.CLUBS
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import com.clubs.generated.jooq.tables.references.USERS
import com.clubs.generated.jooq.tables.references.USER_CLUB_REPUTATION
import com.clubs.reputation.ReputationPolicy
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
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

    override fun findByUserAndClub(userId: UUID, clubId: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
            )
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findById(id: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(MEMBERSHIPS.ID.eq(id))
            .fetchOne()
            ?.let(mapper::toDomain)

    override fun findByUserId(userId: UUID): List<Membership> {
        val now = OffsetDateTime.now()
        return dsl.select(*MEMBERSHIPS.fields())
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(
                        MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period)
                            .or(
                                MEMBERSHIPS.STATUS.eq(MembershipStatus.cancelled)
                                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
                            )
                    )
            )
            .fetchInto(MembershipsRecord::class.java)
            .map(mapper::toDomain)
    }

    override fun findClubMembersWithUserInfo(clubId: UUID, includeCancelledInPeriod: Boolean): List<ClubMemberInfo> {
        val now = OffsetDateTime.now()
        val statusCondition = if (includeCancelledInPeriod) {
            MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                .or(
                    MEMBERSHIPS.STATUS.eq(MembershipStatus.cancelled)
                        .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
                )
        } else {
            MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
        }
        val outcomeCount = DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0))
        // Sort by the DISPLAYED reliability: shown only once a track record exists
        // (outcome_count >= threshold). Newcomers / sub-threshold / owners (no row) sort
        // to the bottom via NULLS LAST — not the top, which raw DESC NULLS-FIRST would do.
        val displayReliability = DSL.`when`(
            outcomeCount.ge(ReputationPolicy.MIN_OUTCOMES_FOR_DISPLAY),
            USER_CLUB_REPUTATION.RELIABILITY_INDEX
        )
        return dsl.select(
            MEMBERSHIPS.USER_ID,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.STATUS,
            MEMBERSHIPS.JOINED_AT,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            USER_CLUB_REPUTATION.RELIABILITY_INDEX,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            outcomeCount.`as`("outcome_count")
        )
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(clubId))
            )
            .where(MEMBERSHIPS.CLUB_ID.eq(clubId).and(statusCondition))
            .orderBy(displayReliability.desc().nullsLast())
            .fetch { r ->
                ClubMemberInfo(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    reliabilityIndex = r.get(USER_CLUB_REPUTATION.RELIABILITY_INDEX),
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    subscriptionCancelled = r.get(MEMBERSHIPS.STATUS) == MembershipStatus.cancelled
                )
            }
    }

    override fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo> =
        dsl.select(
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            CLUBS.CATEGORY,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.JOINED_AT,
            USER_CLUB_REPUTATION.RELIABILITY_INDEX,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS,
            USER_CLUB_REPUTATION.TOTAL_ATTENDANCES,
            USER_CLUB_REPUTATION.SPONTANEITY_COUNT,
            DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0)).`as`("outcome_count")
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
                    reliabilityIndex = r.get(USER_CLUB_REPUTATION.RELIABILITY_INDEX),
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    totalConfirmations = r.get(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS),
                    totalAttendances = r.get(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES),
                    spontaneityCount = r.get(USER_CLUB_REPUTATION.SPONTANEITY_COUNT),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0
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

    override fun isMember(userId: UUID, clubId: UUID): Boolean {
        val now = OffsetDateTime.now()
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess(now))
                )
        )
    }

    override fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean {
        val now = OffsetDateTime.now()
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess(now))
                        .and(CLUBS.IS_ACTIVE.eq(true))
                )
        )
    }

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

    /**
     * Revives a previously dead (cancelled / expired) membership row for a
     * **free** club. UNIQUE(user_id, club_id) means we cannot INSERT a fresh
     * row when one already exists — reactivation is the only path. Resets
     * lifecycle fields so the join is indistinguishable from a brand-new one:
     * status=active, joined_at=now, subscription_expires_at=null (free club —
     * no Stars billing), updated_at=now. `member_count` bookkeeping stays
     * with the caller; see ApplicationService / MembershipService.
     */
    override fun reactivateFree(membershipId: UUID): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .setNull(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .returning()
            .fetchOne()!!
        return mapper.toDomain(record)
    }

    override fun cancel(membershipId: UUID) {
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .set(MEMBERSHIPS.UPDATED_AT, OffsetDateTime.now())
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

    // Telegram IDs of members who currently have access to the club — the shared
    // MembershipAccess predicate (active, or cancelled-but-still-paid). Members
    // without access (expired/grace_period) must not be DM'd about an event they
    // can't open. (GAP-010)
    override fun findMemberTelegramIds(clubId: UUID): List<Long> {
        val now = OffsetDateTime.now()
        return dsl.select(USERS.TELEGRAM_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MembershipAccess.hasAccess(now))
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }
}
