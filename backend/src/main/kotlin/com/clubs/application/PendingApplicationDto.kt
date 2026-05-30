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
    val avatarUrl: String?
)

/**
 * Cross-club aggregate over `user_club_reputation` for one applicant.
 * Backend returns raw counts; phrasing happens on the frontend.
 *
 * Semantics (see docs/modules/reputation.md):
 *  - memberClubCount    = number of clubs with a reputation row for the user.
 *  - totalConfirmations = sum of stage-2 commitments (final "идёт"/"не идёт").
 *  - totalAttendances   = sum of actually-attended events from those commitments.
 */
data class PeerStatsDto(
    val memberClubCount: Int,
    val totalConfirmations: Int,
    val totalAttendances: Int
)

data class ClubBriefDto(
    val id: UUID,
    val name: String,
    val avatarUrl: String?
)

/** Lightweight counter for the BottomTabBar dot indicator. */
data class PendingApplicationsCountDto(val count: Int)
