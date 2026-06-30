package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

interface MembershipRepository {

    // Lookups
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findById(id: UUID): Membership?
    fun findByUserId(userId: UUID): List<Membership>
    fun findClubMembersWithUserInfo(clubId: UUID, includeFrozen: Boolean = false): List<ClubMemberInfo>
    fun findUserClubsWithReputation(userId: UUID): List<UserClubReputationInfo>
    /** `frozen` members across every active club owned by [ownerId] — the cross-club «Ждут оплаты» feed. */
    fun findFrozenMembersByOwner(ownerId: UUID): List<OrganizerDuesMember>
    /** `frozen` members who declared a dues payment (claim pending) across [ownerId]'s clubs — drives the
     *  «Мои клубы» dot so a paid-and-waiting member is noticed without opening the tab. */
    fun countClaimedFrozenByOwner(ownerId: UUID): Int
    fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef?

    // Predicates / counts
    fun isMember(userId: UUID, clubId: UUID): Boolean
    fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean
    fun countActiveByClubId(clubId: UUID): Int
    /** Active members excluding the organizer, across [clubIds] — organizer trust card «доверяют N участников». */
    fun countActiveNonOrganizerMembersInClubs(clubIds: Collection<UUID>): Int

    // Mutations
    fun create(userId: UUID, clubId: UUID): Membership
    fun createFrozen(userId: UUID, clubId: UUID): Membership
    fun createOrganizer(userId: UUID, clubId: UUID): Membership
    fun reactivateFree(membershipId: UUID): Membership
    fun reactivateFrozen(membershipId: UUID): Membership
    fun cancel(membershipId: UUID)
    /** Organizer kick: cancel the membership AND clear the paid window so the member loses access
     *  immediately (no leave-style grace until expiry). Returns rows affected (0 = already gone / race). */
    fun remove(membershipId: UUID): Int
    fun activateSubscription(userId: UUID, clubId: UUID, expiresAt: OffsetDateTime): UUID
    fun renewSubscription(membershipId: UUID, newExpiresAt: OffsetDateTime)

    // Access gate (de-Stars, Slice 2) — organizer-controlled freeze + dues tracking. Each returns
    // rows-affected so the service can guard the optimistic status transition (0 = lost the race → 409).
    fun freezeAccess(membershipId: UUID): Int
    fun unfreezeAccess(membershipId: UUID): Int
    fun markDuesPaid(membershipId: UUID, markedBy: UUID, accessUntil: OffsetDateTime): Int
    fun unmarkDues(membershipId: UUID): Int

    // Member-initiated dues claim (de-Stars): the frozen member declares they paid (method "sbp"/"cash";
    // proofUrl = screenshot for sbp, null for cash). Guarded on status=frozen (0 rows → no longer frozen).
    fun claimDues(membershipId: UUID, method: String, proofUrl: String?): Int

    // Member admin profile (S1) — organizer manually sets the access window end / a private note.
    /** Grants access until a custom date (status→active, clears frozen). Manual override, not a dues confirm. */
    fun setAccessUntil(membershipId: UUID, accessUntil: OffsetDateTime): Int
    /** Sets the private organizer note (null = clears it). */
    fun updateOrganizerNote(membershipId: UUID, note: String?): Int

    // Lifecycle / scheduler (honor-system access window)
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification>
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification>
    /** Drops every `active` membership whose access window (subscription_expires_at) has passed to `frozen`. */
    fun expireOverdueAccess(now: OffsetDateTime): Int
    /** Count of soon-expiring members across [clubIds] — feeds the «Управление» red-dot badge. */
    fun countExpiringSoonByClubs(clubIds: Collection<UUID>, now: OffsetDateTime, threshold: OffsetDateTime): Int
    /** Count of `frozen` members (awaiting dues confirmation) across [clubIds] — also lights the red-dot. */
    fun countFrozenByClubs(clubIds: Collection<UUID>): Int

    // Bot/notification
    fun findMemberTelegramIds(clubId: UUID): List<Long>
}
