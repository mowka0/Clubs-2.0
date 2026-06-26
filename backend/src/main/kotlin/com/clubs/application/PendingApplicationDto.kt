package com.clubs.application

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pending application card for the organizer cross-club inbox on MyClubsPage.
 * Enriched with applicant identity, peer-signal aggregate, and club brief so
 * the frontend renders the list without N+1 follow-ups.
 *
 * See docs/modules/applications-inbox.md § "GET /api/users/me/applications-pending".
 */
data class PendingApplicationDto(
    val applicationId: UUID,
    val answerText: String?,
    val createdAt: OffsetDateTime,
    val hoursUntilAutoReject: Int,
    val applicant: ApplicantInfoDto,
    val peerStats: PeerStatsDto,
    val club: ClubBriefDto
)

data class ApplicantInfoDto(
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val country: String?,
    val city: String?,
    val bio: String?,
    val interests: List<String>
)

/**
 * Cross-club applicant signal for the organizer review card. Backend returns raw counts; phrasing
 * happens on the frontend.
 *
 * Participation counts (from `user_club_reputation`, see docs/modules/reputation.md):
 *  - memberClubCount    = number of clubs with a reputation row for the user.
 *  - totalConfirmations = sum of stage-2 commitments (final "идёт"/"не идёт").
 *  - totalAttendances   = sum of actually-attended events from those commitments.
 *
 * Cross-club reputation (on-read from the ledger, see [com.clubs.reputation.ApplicantSignalService]):
 *  - reliableClubs / trackRecordClubs = the "надёжен в N из M клубов" donut.
 *  - level / levelName / levelTier     = global gamification level (others projection) for the pill.
 */
data class PeerStatsDto(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int,
    val reliableClubs: Int,
    val trackRecordClubs: Int,
    val level: Int,
    val levelName: String,
    val levelTier: String
)

data class ClubBriefDto(
    val id: UUID,
    val name: String,
    val avatarUrl: String?
)

/**
 * Counter feeding the `/my-clubs` tab-dot indicator: pending applications across the caller's owned
 * clubs (organizer action). De-Stars Slice 2 removed the Stars "awaiting payment" counters — approve
 * creates the membership immediately, so that state no longer exists.
 *
 * See docs/modules/applications-inbox.md § "GET /api/users/me/applications-pending-count".
 */
data class PendingApplicationsCountDto(
    val inboxCount: Int
)
