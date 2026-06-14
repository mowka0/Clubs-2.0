import { apiClient } from './apiClient';
import type {
  AwaitingPaymentApplicantDto,
  AwaitingPaymentApplicationDto,
  GamificationDto,
  JoinClubResult,
  LeavePreviewDto,
  MemberListItemDto,
  MembershipDto,
  MemberProfileDto,
  MyReputationDto,
  OrganizerAwaitingPaymentApplicantDto,
  PendingApplicationDto,
  PendingApplicationsCountDto,
} from '../types/api';

export interface ApplicationDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;
  answerText: string | null;
  rejectedReason: string | null;
  createdAt: string | null;
}

export function joinClub(clubId: string): Promise<JoinClubResult> {
  return apiClient.post<JoinClubResult>(`/api/clubs/${clubId}/join`);
}

/**
 * Cancel caller's membership in [clubId]. Backend semantics depend on the
 * club's `subscription_price`:
 *  - Free club  → immediate cascade-cancel (status=cancelled, member_count-=1,
 *                 active skladchinas/event-responses cleared).
 *  - Paid club  → autorenew off (status=cancelled, access kept until
 *                 `subscription_expires_at`, no cascade, member_count unchanged).
 * Errors:
 *  - 400 «Owner cannot leave the club»
 *  - 404 «Membership not found» (not a member, or already cancelled/expired).
 * See docs/modules/club-leave.md.
 */
export function leaveClub(clubId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/leave`);
}

/**
 * Pre-leave preview: how many open obligations (confirmed bookings + pending
 * reputation skladchinas) leaving a free club would break. Drives the warning
 * line in the leave confirm dialog. Paid clubs return all-zero (nothing breaks
 * until the subscription expires). See docs/modules/club-leave.md § "выход-с-обязательствами".
 */
export function getLeavePreview(clubId: string): Promise<LeavePreviewDto> {
  return apiClient.get<LeavePreviewDto>(`/api/clubs/${clubId}/leave-preview`);
}

export function applyToClub(clubId: string, answerText: string): Promise<ApplicationDto> {
  return apiClient.post<ApplicationDto>(`/api/clubs/${clubId}/apply`, { answerText });
}

export function getMyApplications(): Promise<ApplicationDto[]> {
  return apiClient.get<ApplicationDto[]>('/api/users/me/applications');
}

export function joinByInviteCode(code: string): Promise<JoinClubResult> {
  return apiClient.post<JoinClubResult>(`/api/invite/${code}/join`);
}

export interface GetClubMembersOptions {
  /**
   * If true, include paid members who already cancelled their subscription but
   * are still inside the paid period (`subscription_expires_at > now`). Used
   * by the skladchina-create flow to render them as disabled with a
   * «Отменил подписку» tag. Defaults to false — every other caller gets the
   * legacy active-only list.
   */
  includeCancelled?: boolean;
}

export function getClubMembers(
  clubId: string,
  options: GetClubMembersOptions = {},
): Promise<MemberListItemDto[]> {
  const params: Record<string, string> = {};
  if (options.includeCancelled) params.includeCancelled = 'true';
  return apiClient.get<MemberListItemDto[]>(`/api/clubs/${clubId}/members`, params);
}

/**
 * Organizer-only view: applicants for [clubId] whose application is approved
 * but the Stars invoice hasn't been paid yet. Backend returns 403 if caller
 * is not the club owner — frontend additionally gates the call behind the
 * `isOrganizer` flag, but the backend authz is the source of truth.
 */
export function getClubAwaitingPaymentApplicants(
  clubId: string,
): Promise<AwaitingPaymentApplicantDto[]> {
  return apiClient.get<AwaitingPaymentApplicantDto[]>(
    `/api/clubs/${clubId}/awaiting-payment-applicants`,
  );
}

export function getMemberProfile(clubId: string, userId: string): Promise<MemberProfileDto> {
  return apiClient.get<MemberProfileDto>(`/api/clubs/${clubId}/members/${userId}`);
}

export function getMyReputation(): Promise<MyReputationDto> {
  return apiClient.get<MyReputationDto>('/api/users/me/reputation');
}

/** Self-view gamification panel: XP, level + progress, badges. See reputation-v2.md §H3. */
export function getMyGamification(): Promise<GamificationDto> {
  return apiClient.get<GamificationDto>('/api/users/me/gamification');
}

export function getClubApplications(
  clubId: string,
  status?: 'pending' | 'approved' | 'rejected'
): Promise<import('../types/api').ClubApplicationDto[]> {
  const params = status ? { status } : undefined;
  return apiClient.get(`/api/clubs/${clubId}/applications`, params);
}

export function approveApplication(applicationId: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/approve`);
}

/**
 * Reject a pending application. Backend now requires a non-empty `reason`
 * (5–500 chars after trim) — see docs/modules/applications-inbox.md.
 */
export function rejectApplication(applicationId: string, reason: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/reject`, { reason });
}

export function getMyPendingApplications(): Promise<PendingApplicationDto[]> {
  return apiClient.get<PendingApplicationDto[]>('/api/users/me/applications-pending');
}

/**
 * Combined counter that drives the «Мои клубы» tab-dot. Returns both inbox
 * (organizer) and awaiting-payment (applicant) counts in one shape.
 */
export function getMyClubsActionCounts(): Promise<PendingApplicationsCountDto> {
  return apiClient.get<PendingApplicationsCountDto>(
    '/api/users/me/applications-pending-count',
  );
}

/**
 * Caller's own approved-but-unpaid applications — surfaced on MyClubsPage
 * so the applicant can re-trigger the Stars invoice when the original DM
 * was missed.
 */
export function getMyAwaitingPaymentApplications(): Promise<AwaitingPaymentApplicationDto[]> {
  return apiClient.get<AwaitingPaymentApplicationDto[]>(
    '/api/users/me/applications-awaiting-payment',
  );
}

/**
 * Cross-club organizer view: approved-but-unpaid applicants across all clubs
 * the caller owns. Surfaces on MyClubsPage so the organizer doesn't have to
 * enter each club to see who hasn't paid yet. Non-organizers get empty list
 * (server-side filter via `clubs.owner_id`), no 403.
 */
export function getOrganizerAwaitingPaymentApplicants(): Promise<
  OrganizerAwaitingPaymentApplicantDto[]
> {
  return apiClient.get<OrganizerAwaitingPaymentApplicantDto[]>(
    '/api/users/me/organizer/awaiting-payment-applicants',
  );
}

/**
 * Re-send the Stars invoice for an approved-but-unpaid application. Backend
 * rate-limits at 1 call per 60s per application (HTTP 429 «Please wait
 * before resending the invoice»). 204 No Content on success.
 */
export function resendApplicationInvoice(applicationId: string): Promise<void> {
  return apiClient.post<void>(`/api/applications/${applicationId}/resend-invoice`);
}

/**
 * Finalises a free-club membership for an approved application stuck in
 * "approved-without-membership" state (legacy data / earlier free-club approve
 * bug). Backend validates the application is approved AND the club's
 * subscription_price <= 0 AND no active/grace membership exists. Returns the
 * newly-created MembershipDto on success.
 */
export function completeFreeMembership(applicationId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(
    `/api/applications/${applicationId}/complete-free-membership`,
  );
}
