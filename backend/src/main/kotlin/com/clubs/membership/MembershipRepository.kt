package com.clubs.membership

import java.time.OffsetDateTime
import java.util.UUID

interface MembershipRepository {

    // Lookups
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Membership?
    fun findById(id: UUID): Membership?
    fun findByUserId(userId: UUID): List<Membership>
    fun findClubMembersWithUserInfo(clubId: UUID): List<ClubMemberInfo>
    fun findExpiryRefByUserAndClub(userId: UUID, clubId: UUID): MembershipExpiryRef?

    // Predicates / counts
    fun isMember(userId: UUID, clubId: UUID): Boolean
    fun isActiveMemberInActiveClub(userId: UUID, clubId: UUID): Boolean
    fun countActiveByClubId(clubId: UUID): Int

    // Mutations
    fun create(userId: UUID, clubId: UUID): Membership
    fun createOrganizer(userId: UUID, clubId: UUID): Membership
    fun cancel(membershipId: UUID)
    fun activateSubscription(userId: UUID, clubId: UUID, expiresAt: OffsetDateTime): UUID
    fun renewSubscription(membershipId: UUID, newExpiresAt: OffsetDateTime)

    // Lifecycle / scheduler
    fun findExpiringWithin(now: OffsetDateTime, threshold: OffsetDateTime): List<ExpiringSubscriptionNotification>
    fun findActiveExpired(now: OffsetDateTime): List<ExpiringSubscriptionNotification>
    fun moveActiveToGracePeriod(now: OffsetDateTime): Int
    fun findGracePeriodExpiredGroupedByClub(gracePeriodEnd: OffsetDateTime): List<ClubMembershipExpiredCount>
    fun moveGracePeriodToExpired(gracePeriodEnd: OffsetDateTime): Int

    // Bot/notification
    fun findMemberTelegramIds(clubId: UUID): List<Long>
}
