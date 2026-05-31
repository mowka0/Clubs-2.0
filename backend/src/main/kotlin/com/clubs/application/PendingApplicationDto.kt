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

/**
 * Combined counter feeding the `/my-clubs` tab-dot indicator. All three numbers
 * mean "the user has work to do on this tab":
 *  - [inboxCount]                    — pending applications across the caller's owned clubs (organizer action).
 *  - [awaitingPaymentCount]          — caller's own approved applications without active membership (applicant action).
 *  - [organizerAwaitingPaymentCount] — approved applicants of the caller's owned clubs who haven't paid yet (organizer visibility).
 *
 * Single endpoint = single source of truth, one cache slot. See
 * docs/modules/applications-inbox.md § "GET /api/users/me/applications-pending-count".
 */
data class PendingApplicationsCountDto(
    val inboxCount: Int,
    val awaitingPaymentCount: Int,
    val organizerAwaitingPaymentCount: Int
)

/**
 * Caller's own application that was approved but the Stars invoice hasn't been
 * paid yet (no active membership exists). Surfaces in MyClubsPage so the user
 * can re-trigger invoice delivery from the Mini App.
 *
 * See docs/modules/applications-inbox.md § "GET /api/users/me/applications-awaiting-payment".
 */
data class AwaitingPaymentApplicationDto(
    val applicationId: UUID,
    val approvedAt: OffsetDateTime,
    val club: ClubBriefDto,
    val subscriptionPrice: Int
)

/**
 * Mirror of [AwaitingPaymentApplicationDto] but from the organizer's perspective:
 * an applicant whose application is approved for the organizer's club, but who
 * has not paid the Stars invoice yet (no active/grace_period membership).
 *
 * Surfaces on `ClubMembersTab` (organizer view) so the full lifecycle —
 * applicant → member — is visible in one place.
 *
 * See docs/modules/applications-inbox.md § "GET /api/clubs/{clubId}/awaiting-payment-applicants".
 */
data class AwaitingPaymentApplicantDto(
    val applicationId: UUID,
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val approvedAt: OffsetDateTime
)

/**
 * Cross-club organizer view of approved-but-unpaid applicants — surfaces on
 * MyClubsPage so an organizer with multiple clubs sees pending payments in one
 * place without entering each club. Fields are intentionally minimal (row-only
 * rendering, no modal opens from here): applicant identity for the row text,
 * club brief for the meta line, subscription price for context.
 *
 * See docs/modules/applications-inbox.md § "GET /api/users/me/organizer/awaiting-payment-applicants".
 */
data class OrganizerAwaitingPaymentApplicantDto(
    val applicationId: UUID,
    val approvedAt: OffsetDateTime,
    val userId: UUID,
    val firstName: String,
    val lastName: String?,
    val telegramUsername: String?,
    val avatarUrl: String?,
    val club: ClubBriefDto,
    val subscriptionPrice: Int
)
