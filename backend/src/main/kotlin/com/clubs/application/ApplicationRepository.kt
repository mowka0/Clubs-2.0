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

    /**
     * Club soft-delete cascade: hard-deletes every pending/approved application to [clubId]
     * (for all users) so they stop appearing as orphan rows in applicants' «Мои заявки» — the
     * club is gone, the request is moot. Rejected / auto_rejected rows are preserved as audit
     * history, mirroring [deleteActiveByUserAndClub]. Returns rows deleted.
     */
    fun deleteActiveByClub(clubId: UUID): Int

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

    // Counts / rate limit
    fun countTodayByUser(userId: UUID): Int

    // Scheduler
    fun findPendingOlderThan(cutoff: OffsetDateTime): List<Application>
    fun markAutoRejected(cutoff: OffsetDateTime): Int
}
