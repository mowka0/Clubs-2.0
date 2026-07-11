package com.clubs.application

import com.clubs.club.Club
import com.clubs.generated.jooq.tables.records.ApplicationsRecord
import com.clubs.generated.jooq.tables.records.UsersRecord
import com.clubs.reputation.ApplicantSignal
import com.clubs.reputation.PeerStatsAggregate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

private const val AUTO_REJECT_AFTER_HOURS = 48L

@Component
class ApplicationMapper {

    fun toDomain(record: ApplicationsRecord): Application = Application(
        id = record.id!!,
        userId = record.userId,
        clubId = record.clubId,
        answerText = record.answerText,
        status = record.status!!,
        rejectedReason = record.rejectedReason,
        createdAt = record.createdAt!!,
        resolvedAt = record.resolvedAt
    )

    fun toDto(application: Application): ApplicationDto = ApplicationDto(
        id = application.id,
        userId = application.userId,
        clubId = application.clubId,
        status = application.status.literal,
        answerText = application.answerText,
        rejectedReason = application.rejectedReason,
        createdAt = application.createdAt,
        resolvedAt = application.resolvedAt
    )

    fun toApplicantInfo(record: UsersRecord, interests: List<String>): ApplicantInfoDto = ApplicantInfoDto(
        userId = record.id!!,
        firstName = record.firstName,
        lastName = record.lastName,
        telegramUsername = record.telegramUsername,
        avatarUrl = record.avatarUrl,
        country = record.country,
        city = record.city,
        bio = record.bio,
        interests = interests
    )

    fun toPeerStats(aggregate: PeerStatsAggregate, signal: ApplicantSignal): PeerStatsDto = PeerStatsDto(
        memberClubCount = aggregate.memberClubCount,
        totalConfirmations = aggregate.totalConfirmations,
        totalAttendances = aggregate.totalAttendances,
        reliableClubs = signal.reliableClubs,
        trackRecordClubs = signal.trackRecordClubs,
        level = signal.level,
        levelName = signal.levelName,
        levelTier = signal.levelTier
    )

    fun toClubBrief(club: Club): ClubBriefDto = ClubBriefDto(
        id = club.id,
        name = club.name,
        avatarUrl = club.avatarUrl,
        memberCount = club.memberCount,
        memberLimit = club.memberLimit
    )

    fun toPendingDto(
        application: Application,
        applicant: ApplicantInfoDto,
        peerStats: PeerStatsDto,
        club: ClubBriefDto,
        now: OffsetDateTime
    ): PendingApplicationDto = PendingApplicationDto(
        applicationId = application.id,
        answerText = application.answerText,
        createdAt = application.createdAt,
        hoursUntilAutoReject = hoursUntilAutoReject(application.createdAt, now),
        applicant = applicant,
        peerStats = peerStats,
        club = club
    )

    private fun hoursUntilAutoReject(createdAt: OffsetDateTime, now: OffsetDateTime): Int {
        val deadline = createdAt.plusHours(AUTO_REJECT_AFTER_HOURS)
        val remaining = Duration.between(now, deadline).toHours()
        return remaining.coerceAtLeast(0).toInt()
    }
}
