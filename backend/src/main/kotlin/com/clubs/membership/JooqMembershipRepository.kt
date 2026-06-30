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

    // "Currently-belongs" lookup for management/leave/join-idempotency: a `frozen` member
    // (organizer gated them pending off-platform dues) still belongs and must be findable so
    // they can be unfrozen, leave, or be told "already a member" on a re-join attempt. This is
    // wider than MembershipAccess.hasAccess (content access) on purpose.
    override fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership? =
        dsl.selectFrom(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                    .and(
                        MEMBERSHIPS.STATUS.`in`(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.grace_period
                        )
                    )
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

    // "My clubs" list: clubs the user currently belongs to. `frozen` is included — a gated
    // member must still see the club (to learn they're frozen / pay dues). Drops the old
    // cancelled-but-still-paid branch (de-Stars: subscription_expires_at is no longer a driver).
    override fun findByUserId(userId: UUID): List<Membership> {
        return dsl.select(*MEMBERSHIPS.fields())
            .from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                MEMBERSHIPS.USER_ID.eq(userId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(
                        MEMBERSHIPS.STATUS.`in`(
                            MembershipStatus.active,
                            MembershipStatus.frozen,
                            MembershipStatus.grace_period
                        )
                    )
            )
            .fetchInto(MembershipsRecord::class.java)
            .map(mapper::toDomain)
    }

    override fun findClubMembersWithUserInfo(clubId: UUID, includeFrozen: Boolean): List<ClubMemberInfo> {
        // The organizer dashboard needs `frozen` members too (they show in «Ждут оплаты»); the member-
        // facing roster on ClubPage sees `active` only. includeFrozen is set by the caller (MemberService)
        // from whether the viewer is the organizer.
        val statusCondition = if (includeFrozen) {
            MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen)
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
            MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT,
            MEMBERSHIPS.DUES_CLAIMED_AT,
            MEMBERSHIPS.DUES_CLAIM_METHOD,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            USER_CLUB_REPUTATION.PROMISE_FULFILLMENT_PCT,
            USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS,
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
                    totalConfirmations = r.get(USER_CLUB_REPUTATION.TOTAL_CONFIRMATIONS),
                    outcomeCount = r.get("outcome_count", Int::class.java) ?: 0,
                    status = r.get(MEMBERSHIPS.STATUS) ?: MembershipStatus.active,
                    subscriptionExpiresAt = r.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT),
                    duesClaimedAt = r.get(MEMBERSHIPS.DUES_CLAIMED_AT),
                    duesClaimMethod = r.get(MEMBERSHIPS.DUES_CLAIM_METHOD)
                )
            }
    }

    override fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo> {
        val outcomeCount = DSL.coalesce(USER_CLUB_REPUTATION.OUTCOME_COUNT, DSL.`val`(0))
        // "Active" in the profile = member still belongs AND the club is alive. `frozen` counts as
        // belonging (gated pending dues, still the user's club). Everything else that survives below
        // (a left/expired membership) is "История" — it appears only because a reputation track
        // record (outcome_count > 0) lives on. P1b: the global aggregate is all-history, so this query
        // no longer drops left clubs (closes the active-only hole A). De-Stars: dropped the old
        // cancelled-but-still-paid branch (subscription_expires_at is no longer an access driver).
        val activeCondition = MEMBERSHIPS.STATUS.`in`(
            MembershipStatus.active,
            MembershipStatus.frozen,
            MembershipStatus.grace_period
        ).and(CLUBS.IS_ACTIVE.eq(true))
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
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess())
                )
        )
    }

    override fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(MEMBERSHIPS)
                .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
                .where(
                    MEMBERSHIPS.USER_ID.eq(userId)
                        .and(MEMBERSHIPS.CLUB_ID.eq(clubId))
                        .and(MembershipAccess.hasAccess())
                        .and(CLUBS.IS_ACTIVE.eq(true))
                )
        )
    }

    // Occupied-slot count for the member-limit check. `frozen` counts: a paid-club join lands a
    // member straight into `frozen` (gated pending dues), and that still holds a slot — otherwise N
    // people could pile into a 1-slot club while all frozen and bypass the limit.
    override fun countActiveByClubId(clubId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen))
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun countActiveNonOrganizerMembersInClubs(clubIds: Collection<UUID>): Int {
        if (clubIds.isEmpty()) return 0
        // active (= currently have access, real social proof) + role != organizer (don't count the owner).
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                    .and(MEMBERSHIPS.ROLE.ne(MembershipRole.organizer))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    // Free-club join → `active`. A free membership has NO subscription → subscription_expires_at stays
    // NULL. (Setting a 30-day expiry here was the historical bug that made every free member look like
    // a cancelled-in-period paid subscriber.) The paid join is createFrozen below.
    override fun create(userId: UUID, clubId: UUID): Membership =
        insertMembership(userId, clubId, MembershipStatus.active)

    // Paid-club join → `frozen` (de-Stars Slice 2): the member belongs and occupies a slot, but has no
    // content access until the organizer confirms the off-platform dues (AccessGateService.markDuesPaid).
    override fun createFrozen(userId: UUID, clubId: UUID): Membership =
        insertMembership(userId, clubId, MembershipStatus.frozen)

    private fun insertMembership(userId: UUID, clubId: UUID, status: MembershipStatus): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.insertInto(MEMBERSHIPS)
            .set(MEMBERSHIPS.ID, UUID.randomUUID())
            .set(MEMBERSHIPS.USER_ID, userId)
            .set(MEMBERSHIPS.CLUB_ID, clubId)
            .set(MEMBERSHIPS.STATUS, status)
            .set(MEMBERSHIPS.ROLE, MembershipRole.member)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, if (status == MembershipStatus.frozen) now else null)
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
     * Revives a previously dead (cancelled / expired) membership row. UNIQUE(user_id, club_id) means we
     * cannot INSERT a fresh row when one already exists — reactivation is the only path. Resets lifecycle
     * fields so the join is indistinguishable from a brand-new one. [reactivateFree] → `active` (free
     * club, no billing); [reactivateFrozen] → `frozen` (paid club, gated pending organizer dues).
     */
    override fun reactivateFree(membershipId: UUID): Membership =
        reactivate(membershipId, MembershipStatus.active)

    override fun reactivateFrozen(membershipId: UUID): Membership =
        reactivate(membershipId, MembershipStatus.frozen)

    private fun reactivate(membershipId: UUID, status: MembershipStatus): Membership {
        val now = OffsetDateTime.now()
        val record = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, status)
            .set(MEMBERSHIPS.JOINED_AT, now)
            .setNull(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT)
            // Fresh join: clear any stale dues markers from the prior lifecycle; set frozen-since when frozen.
            .setNull(MEMBERSHIPS.DUES_MARKED_PAID_AT)
            .setNull(MEMBERSHIPS.DUES_MARKED_BY)
            // Also drop a prior member-side dues claim — otherwise a re-joiner who claimed before being
            // rejected reappears already on «Оплата на проверке» instead of the fresh «Оплатить взнос».
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, if (status == MembershipStatus.frozen) now else null)
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
            // A dead membership carries no live dues claim — clearing here resolves «Отказать и вернуть»
            // (and any leave) immediately, so a stale claim can't linger or follow a re-join.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetchOne()
        if (row != null) {
            history.record(row.get(MEMBERSHIPS.USER_ID)!!, row.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.left, now)
        }
    }

    override fun remove(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        // Kick: cancel + null the paid window so the frontend grace («cancelled but paid until X») never
        // applies — a removed member is fully out, unlike a voluntary leaver who keeps access until expiry.
        val row = dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.cancelled)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, null as OffsetDateTime?)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.ne(MembershipStatus.cancelled)))
            .returningResult(MEMBERSHIPS.USER_ID, MEMBERSHIPS.CLUB_ID)
            .fetchOne()
        if (row != null) {
            history.record(row.get(MEMBERSHIPS.USER_ID)!!, row.get(MEMBERSHIPS.CLUB_ID)!!, MembershipEvent.left, now)
            return 1
        }
        return 0
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

    // Access-gate mutations (de-Stars, Slice 2). Deliberately NOT written to membership_history:
    // a freeze/unfreeze is temporary access suspension, not a join/leave/expire churn event (there is
    // no MembershipEvent for it). A frozen member still belongs; when they actually leave, cancel()
    // logs `left` as usual — so the retention/churn log stays accurate without these.
    override fun freezeAccess(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.frozen)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, now)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active)))
            .execute()
    }

    override fun unfreezeAccess(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            // Reopening access also resolves any pending dues claim.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen)))
            .execute()
    }

    override fun claimDues(membershipId: UUID, method: String, proofUrl: String?): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.DUES_CLAIMED_AT, now)
            .set(MEMBERSHIPS.DUES_CLAIM_METHOD, method)
            .set(MEMBERSHIPS.DUES_PROOF_URL, proofUrl)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            // Only a frozen (gated) member claims; 0 rows = no longer frozen (e.g. organizer just admitted).
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen)))
            .execute()
    }

    // "Взнос получен": records the off-platform dues payment, grants access (status→active, clears frozen)
    // AND sets the access window end (subscription_expires_at = accessUntil) in one atomic step. The
    // scheduler later auto-expires overdue access back to frozen — see expireOverdueAccess.
    override fun markDuesPaid(membershipId: UUID, markedBy: UUID, accessUntil: OffsetDateTime): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            .set(MEMBERSHIPS.DUES_MARKED_PAID_AT, now)
            .set(MEMBERSHIPS.DUES_MARKED_BY, markedBy)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, accessUntil)
            // Granting access resolves any pending dues claim — clear it so it leaves «Ждут оплаты».
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(
                MEMBERSHIPS.ID.eq(membershipId)
                    .and(MEMBERSHIPS.STATUS.`in`(MembershipStatus.active, MembershipStatus.frozen))
            )
            .execute()
    }

    // Clears the dues record only; does NOT re-freeze (symmetric with skladchina un-mark not
    // auto-closing). Guard on dues_marked_paid_at makes a repeat un-mark a no-op (service → idempotent).
    override fun unmarkDues(membershipId: UUID): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .setNull(MEMBERSHIPS.DUES_MARKED_PAID_AT)
            .setNull(MEMBERSHIPS.DUES_MARKED_BY)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId).and(MEMBERSHIPS.DUES_MARKED_PAID_AT.isNotNull))
            .execute()
    }

    // "Своя дата": organizer manually sets the access window end. Grants access (active, clears frozen)
    // without recording a dues payment — it's an admin override, not a «взнос получен» event.
    override fun setAccessUntil(membershipId: UUID, accessUntil: OffsetDateTime): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.active)
            .setNull(MEMBERSHIPS.ACCESS_FROZEN_AT)
            .set(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT, accessUntil)
            // Granting access resolves any pending dues claim.
            .setNull(MEMBERSHIPS.DUES_CLAIMED_AT)
            .setNull(MEMBERSHIPS.DUES_CLAIM_METHOD)
            .setNull(MEMBERSHIPS.DUES_PROOF_URL)
            .set(MEMBERSHIPS.UPDATED_AT, now)
            .where(MEMBERSHIPS.ID.eq(membershipId))
            .execute()
    }

    override fun updateOrganizerNote(membershipId: UUID, note: String?): Int {
        val now = OffsetDateTime.now()
        return dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.ORGANIZER_NOTE, note)
            .set(MEMBERSHIPS.UPDATED_AT, now)
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

    // Honor-system auto-expiry: an `active` paid membership whose access window has passed drops to
    // `frozen` ("ждёт оплаты") — keeps belonging, loses content access until the next confirmed dues.
    // Free memberships (subscription_expires_at IS NULL) are excluded. Not logged to membership_history:
    // a freeze is access suspension, not a churn event (same rule as the manual freeze/unfreeze above).
    override fun expireOverdueAccess(now: OffsetDateTime): Int =
        dsl.update(MEMBERSHIPS)
            .set(MEMBERSHIPS.STATUS, MembershipStatus.frozen)
            .set(MEMBERSHIPS.ACCESS_FROZEN_AT, now)
            .where(
                MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.isNotNull)
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(now))
            )
            .execute()

    override fun countExpiringSoonByClubs(clubIds: Collection<UUID>, now: OffsetDateTime, threshold: OffsetDateTime): Int {
        if (clubIds.isEmpty()) return 0
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.active))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.greaterThan(now))
                    .and(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT.lessOrEqual(threshold))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun countFrozenByClubs(clubIds: Collection<UUID>): Int {
        if (clubIds.isEmpty()) return 0
        return dsl.selectCount().from(MEMBERSHIPS)
            .where(
                MEMBERSHIPS.CLUB_ID.`in`(clubIds)
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen))
            )
            .fetchOne(0, Int::class.java) ?: 0
    }

    override fun countClaimedFrozenByOwner(ownerId: UUID): Int =
        dsl.selectCount().from(MEMBERSHIPS)
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                CLUBS.OWNER_ID.eq(ownerId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen))
                    .and(MEMBERSHIPS.DUES_CLAIMED_AT.isNotNull)
            )
            .fetchOne(0, Int::class.java) ?: 0

    override fun findFrozenMembersByOwner(ownerId: UUID): List<OrganizerDuesMember> {
        return dsl.select(
            MEMBERSHIPS.USER_ID,
            USERS.FIRST_NAME,
            USERS.LAST_NAME,
            USERS.AVATAR_URL,
            USERS.TELEGRAM_USERNAME,
            CLUBS.ID,
            CLUBS.NAME,
            CLUBS.AVATAR_URL,
            MEMBERSHIPS.JOINED_AT,
            MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT,
            MEMBERSHIPS.DUES_CLAIMED_AT,
            MEMBERSHIPS.DUES_CLAIM_METHOD
        )
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .join(CLUBS).on(CLUBS.ID.eq(MEMBERSHIPS.CLUB_ID))
            .where(
                CLUBS.OWNER_ID.eq(ownerId)
                    .and(CLUBS.IS_ACTIVE.eq(true))
                    .and(MEMBERSHIPS.STATUS.eq(MembershipStatus.frozen))
            )
            .orderBy(MEMBERSHIPS.JOINED_AT.desc())
            .fetch { r ->
                OrganizerDuesMember(
                    userId = r.get(MEMBERSHIPS.USER_ID)!!,
                    firstName = r.get(USERS.FIRST_NAME),
                    lastName = r.get(USERS.LAST_NAME),
                    avatarUrl = r.get(USERS.AVATAR_URL),
                    telegramUsername = r.get(USERS.TELEGRAM_USERNAME),
                    clubId = r.get(CLUBS.ID)!!,
                    clubName = r.get(CLUBS.NAME)!!,
                    clubAvatarUrl = r.get(CLUBS.AVATAR_URL),
                    joinedAt = r.get(MEMBERSHIPS.JOINED_AT)!!,
                    subscriptionExpiresAt = r.get(MEMBERSHIPS.SUBSCRIPTION_EXPIRES_AT),
                    duesClaimedAt = r.get(MEMBERSHIPS.DUES_CLAIMED_AT),
                    duesClaimMethod = r.get(MEMBERSHIPS.DUES_CLAIM_METHOD)
                )
            }
    }

    // Telegram IDs of members who currently have access to the club — the shared
    // MembershipAccess predicate (status `active`). Members without access
    // (frozen/expired/grace_period) must not be DM'd about an event they can't open. (GAP-010)
    override fun findMemberTelegramIds(clubId: UUID): List<Long> {
        return dsl.select(USERS.TELEGRAM_ID)
            .from(MEMBERSHIPS)
            .join(USERS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID))
            .where(
                MEMBERSHIPS.CLUB_ID.eq(clubId)
                    .and(MembershipAccess.hasAccess())
            )
            .fetch(USERS.TELEGRAM_ID)
            .filterNotNull()
    }
}
