package com.clubs.application

import com.clubs.club.Club
import com.clubs.generated.jooq.tables.records.ApplicationsRecord
import com.clubs.generated.jooq.tables.records.UsersRecord
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

    fun toPeerStats(aggregate: PeerStatsAggregate): PeerStatsDto = PeerStatsDto(
        memberClubCount = aggregate.memberClubCount,
        totalConfirmations = aggregate.totalConfirmations,
        totalAttendances = aggregate.totalAttendances
    )

    fun toClubBrief(club: Club): ClubBriefDto = ClubBriefDto(
        id = club.id,
        name = club.name,
        avatarUrl = club.avatarUrl
    )

    fun toAwaitingPaymentDto(
        application: Application,
        club: ClubBriefDto,
        subscriptionPrice: Int
    ): AwaitingPaymentApplicationDto = AwaitingPaymentApplicationDto(
        applicationId = application.id,
        // Caller invariant: only call this for status=approved applications,
        // which have resolved_at set by updateStatus. Defensive fallback to
        // createdAt to keep the DTO non-null without throwing.
        approvedAt = application.resolvedAt ?: application.createdAt,
        club = club,
        subscriptionPrice = subscriptionPrice
    )

    fun toAwaitingPaymentApplicant(
        application: Application,
        applicant: UsersRecord
    ): AwaitingPaymentApplicantDto = AwaitingPaymentApplicantDto(
        applicationId = application.id,
        userId = applicant.id!!,
        firstName = applicant.firstName,
        lastName = applicant.lastName,
        telegramUsername = applicant.telegramUsername,
        avatarUrl = applicant.avatarUrl,
        // Same invariant as toAwaitingPaymentDto: status=approved guarantees
        // resolvedAt is set. Defensive fallback to createdAt for safety.
        approvedAt = application.resolvedAt ?: application.createdAt
    )

    fun toOrganizerAwaitingPayment(
        application: Application,
        applicant: UsersRecord,
        club: ClubBriefDto,
        subscriptionPrice: Int
    ): OrganizerAwaitingPaymentApplicantDto = OrganizerAwaitingPaymentApplicantDto(
        applicationId = application.id,
        // Same invariant as toAwaitingPaymentDto: status=approved guarantees
        // resolvedAt is set; defensive fallback to createdAt keeps the DTO
        // non-null without throwing.
        approvedAt = application.resolvedAt ?: application.createdAt,
        userId = applicant.id!!,
        firstName = applicant.firstName,
        lastName = applicant.lastName,
        telegramUsername = applicant.telegramUsername,
        avatarUrl = applicant.avatarUrl,
        club = club,
        subscriptionPrice = subscriptionPrice
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
