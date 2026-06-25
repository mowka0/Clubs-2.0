package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipEvent
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
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JooqMembershipRepository(
    private val dsl: DSLContext,
    private val mapper: MembershipMapper,
    // Deliberate: every membership status mutation goes through THIS repository, so writing the
    // append-only history here (the single chokepoint, same transaction) is how the log can never
    // silently miss a transition. Mapping status changes → {joined,left,rejoined,expired} lives here
    // on purpose — do not hoist it to the service layer (that would scatter it and re-open the gap).
    private val history: MembershipHistoryRepository
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
        // Order is a stable base only: MemberService re-sorts by the displayed Trust (computed on
        // read from the ledger), which the SQL cannot express. outcome_count gates "Новичок".
        return dsl.select(
            MEMBERSHIPS.USER_ID,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.STATUS,
            MEMBERSHIPS.JOINED_AT,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
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
            .orderBy(MEMBERSHIPS.JOINED_AT.desc())
            .fetch { r ->
                ClubMemberInfo(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    role = r.get(MEMBERSHIPS.ROLE) ?: MembershipRole.member,
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    subscriptionCancelled = r.get(MEMBERSHIPS.STATUS) == MembershipStatus.cancelled
                )
            }
    }

    override fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo> {
        val now = OffsetDateTime.now()
        val outcomeCount = DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0))
        // "Active" in the profile = member still has access AND the club is alive. Everything else
        // that survives below (a left/expired membership) is "История" — it appears only because a
        // reputation track record (outcome_count > 0) lives on. P1b: the global aggregate is
        // all-history, so this query no longer drops left clubs (closes the active-only hole A).
        val activeCondition = MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.grace_period)
            .or(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.cancelled)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
            )
            .and(CLUBS.IS_ACTIVE.eq(true))
        val activeField = DSL.field(activeCondition).`as`("active")
        return dsl.select(
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            CLUBS.CATEGORY,
            MEMBERSHIPS.ROLE,
            MEMBERSHIPS.JOINED_AT,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS,
            USER_CLUB_REPUTATION.TOTAL_ATTENDANCES,
            USER_CLUB_REPUTATION.SPONTANEITY_COUNT,
            outcomeCount.`as`("outcome_count"),
            activeField
        )
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .leftJoin(USER_CLUB_REPUTATION).on(
                USER_CLUB_REPUTATION.USER_ID.eq(MEMBERSHIPS.USER_ID)
                    .and(USER_CLUB_REPUTATION.CLUB_ID.eq(CLUBS.ID))
            )
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(activeCondition.or(outcomeCount.gt(0)))
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
                    promiseFulfillmentPct = r.get(USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT),
                    totalConfirmations = r.get(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS),
                    totalAttendances = r.get(USER_CLUB_REPUTATION.TOTAL_ATTENDANCES),
                    spontaneityCount = r.get(USER_CLUB_REPUTATION.SPONTANEITY_COUNT),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    active = r.get(activeField) ?: false
                )
            }
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
        // Free-club membership (the only caller is FreeMembershipActivator). A free membership has
        // NO subscription → subscription_expires_at stays NULL. Setting a 30-day expiry here (the
        // historical bug) made every free member look like a cancelled-in-period paid subscriber:
        // phantom "доступ до DATE" banner, and a free leaver lingering as a member for 30 days.
        // The paid path is activateSubscription (real Stars-billed expiry); reactivateFree also nulls it.
        val now = OffsetDateTime.now()
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .returning()
            .fetchOne()!!
        history.record(userId, clubId, MembershipEvent.joined, now)
        return mapper.toDomain(record)
    }

    override fun createOrganizer(userId: UUID, clubId: UUID): Membership {
        // NOT logged to membership_history: the organizer is the owner — structurally always present,
        // never joins or churns in the retention sense (owner cannot leave). Keeping the log
        // member-only means a future retention reader needs no role filter.
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
     * no Stars billing), updated_at=now.
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
        history.record(record.userId, record.clubId, MembershipEvent.rejoined, now)
        return mapper.toDomain(record)
    }

    override fun cancel(membershipId: UUID) {
        val now = OffsetDateTime.now()
        val row = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetchOne()
        if (row != null) {
            history.record(row.get(MEMBERSHIPS.USER_ID)!!, row.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.left, now)
        }
    }

    override fun activateSubscription(userId: UUID, clubId: UUID, expiresAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, id)
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, expiresAt)
            .execute()
        history.record(userId, clubId, MembershipEvent.joined, now)
        return id
    }

    /**
     * Sets active + new expiry. This is BOTH the paid-renewal path (prior status active/grace_period —
     * the member never lost access → not a churn event, nothing logged) and the paid-rejoin path
     * (prior status cancelled/expired → the dead membership comes back → `rejoined`). The prior status
     * is read before the update to tell the two apart.
     */
    override fun renewSubscription(membershipId: UUID, newExpiresAt: OffsetDateTime) {
        val now = OffsetDateTime.now()
        val prior = dsl.select(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID, MEMBERSHIPS.STATUS)
            .from(MEMBERSHIPS)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .fetchOne()
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, newExpiresAt)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
        val priorStatus = prior?.get(MEMBERSHIPS.STATUS)
        if (priorStatus == MembershipStatus.cancelled || priorStatus == MembershipStatus.expired) {
            history.record(prior.get(MEMBERSHIPS.USER_ID)!!, prior.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.rejoined, now)
        }
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

    override fun moveGracePeriodToExpired(gracePeriodEnd: OffsetDateTime): Int {
        val now = OffsetDateTime.now()
        val expired = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.expired)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.grace_period)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(gracePeriodEnd))
            )
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetch()
        expired.forEach {
            history.record(it.get(MEMBERSHIPS.USER_ID)!!, it.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.expired, now)
        }
        return expired.size
    }

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
