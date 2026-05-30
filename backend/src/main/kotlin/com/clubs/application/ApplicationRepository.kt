package com.clubs.application

import com.clubs.generated.jooq.enums.ApplicationStatus
import java.time.OffsetDateTime
import java.util.UUID

interface ApplicationRepository {

    // Mutations
    fun create(userId: UUID, clubId: UUID, answerText: String?): Application
    fun updateStatus(id: UUID, status: ApplicationStatus, reason: String? = null): Application

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
