package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import java.time.OffsetDateTime
import java.util.UUID

interface ApplicationRepository {

    // Mutations
    fun create(userId: UUID, clubId: UUID, answerText: String?): Application
    fun updateStatus(id: UUID, status: ApplicationStatus, reason: String? = null): Application

    /**
     * Hard-deletes any pending or approved application row for the (user, club)
     * pair. Used by `MembershipService.leaveClub` so a returning user has to
     * re-apply from scratch (free private club) and is no longer surfaced in
     * the «Ожидают оплаты» list with a stale approval (paid club). Rejected /
     * auto_rejected rows are preserved — they remain as audit history for the
     * organizer's inbox decisions.
     */
    fun deleteActiveByUserAndClub(userId: UUID, clubId: UUID): Int

    // Lookups
    fun findById(id: UUID): Application?
    fun findByClubId(clubId: UUID, status: ApplicationStatus?): List<Application>
    fun findActiveByUserAndClub(userId: UUID, clubId: UUID): Application?
    fun findByUserId(userId: UUID): List<Application>

    /**
     * Pending applications across multiple clubs, oldest-first.
     * Used by the cross-club organizer inbox. Empty input → empty output (no SQL hit).
     */
    fun findPendingByClubIds(clubIds: Collection<UUID>): List<Application>

    /** Count of pending applications across multiple clubs. Empty input → 0 (no SQL hit). */
    fun countPendingByClubIds(clubIds: Collection<UUID>): Int

    /**
     * Applications by user that are approved but have no active/grace-period membership
     * in the same club (i.e. Stars invoice sent but payment not completed).
     * Ordered by `resolved_at` DESC. Used by «Ожидают оплаты» on MyClubsPage.
     */
    fun findApprovedWithoutMembershipByUserId(userId: UUID): List<Application>

    /**
     * Mirror of [findApprovedWithoutMembershipByUserId] but scoped to a single club:
     * approved-but-unpaid applications for the given [clubId]. The paid-club filter
     * (`subscription_price > 0`) is enforced inside — free clubs never produce this
     * state because membership is created synchronously on approve.
     * Ordered by `resolved_at` DESC. Used by `ClubMembersTab` organizer view.
     */
    fun findApprovedWithoutMembershipByClubId(clubId: UUID): List<Application>

    /**
     * Cross-club mirror of [findApprovedWithoutMembershipByClubId] for the
     * cross-club organizer view on MyClubsPage. Same filters (paid clubs only,
     * NOT EXISTS active/grace_period membership), ordered by `resolved_at` DESC.
     * Empty input → empty output (no SQL hit).
     */
    fun findApprovedWithoutMembershipByClubIds(clubIds: Collection<UUID>): List<Application>

    // Counts / rate limit
    fun countTodayByUser(userId: UUID): Int

    // Scheduler
    fun findPendingOlderThan(cutoff: OffsetDateTime): List<Application>
    fun markAutoRejected(cutoff: OffsetDateTime): Int
}
