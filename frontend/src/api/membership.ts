import { apiClient } from './apiClient';
import type {
  GamificationDto,
  LeavePreviewDto,
  MemberAttentionDto,
  MemberListItemDto,
  MembershipDto,
  MemberProfileDto,
  MyReputationDto,
  OrganizerDuesMemberDto,
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

/**
 * Join an open club. De-Stars Slice 2: always 201 + MembershipDto. A paid club lands the
 * membership in `frozen` (no content access until the organizer confirms the dues offline);
 * a free club lands it `active`. No Stars invoice is created anymore.
 */
export function joinClub(clubId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/join`);
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

/** Join via an invite code. Same de-Stars contract as {@link joinClub}: 201 + MembershipDto,
 *  paid clubs land `frozen`. */
export function joinByInviteCode(code: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/invite/${code}/join`);
}

export function getClubMembers(clubId: string): Promise<MemberListItemDto[]> {
  return apiClient.get<MemberListItemDto[]>(`/api/clubs/${clubId}/members`);
}

/**
 * Organizer access gate (de-Stars Slice 2). All four are owner-only (`@RequiresOrganizer`),
 * return the updated `MembershipDto`, and 409 on a lost status-transition race.
 *  - dues-paid : «Взнос получен» — open access + extend the paid window +30d from max(now, current end).
 *  - freeze    : «Закрыть доступ» — active → frozen.
 *  - unfreeze  : frozen → active without extending the window.
 *  - dues-unpaid: clear the dues mark (does not touch access/window).
 */
export function markMemberDuesPaid(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/dues-paid`);
}

export function freezeMember(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/freeze`);
}

export function unfreezeMember(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/unfreeze`);
}

export function unmarkMemberDues(clubId: string, userId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/dues-unpaid`);
}

/**
 * Member admin profile (S1), owner-only:
 *  - setMemberAccessUntil — set a custom access-window end («своя дата»); `until` is an ISO datetime,
 *    must be in the future (backend rejects past dates).
 *  - updateMemberNote — set/clear the private organizer note (null/blank clears it).
 */
export function setMemberAccessUntil(clubId: string, userId: string, until: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/access-until`, { until });
}

export function updateMemberNote(clubId: string, userId: string, note: string | null): Promise<MembershipDto> {
  return apiClient.patch<MembershipDto>(`/api/clubs/${clubId}/members/${userId}/note`, { note });
}

/** Red-dot feed for [clubId]: soon-expiring + frozen-awaiting-dues counts. Owner-only. */
export function getMemberAttention(clubId: string): Promise<MemberAttentionDto> {
  return apiClient.get<MemberAttentionDto>(`/api/clubs/${clubId}/member-attention`);
}

/** Cross-club «Ждут оплаты»: frozen members across all clubs the caller owns. Empty for non-owners. */
export function getOrganizerAwaitingDues(): Promise<OrganizerDuesMemberDto[]> {
  return apiClient.get<OrganizerDuesMemberDto[]>('/api/users/me/organizer/awaiting-dues');
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
 * Counter that drives the «Мои клубы» tab-dot: organizer-side pending applications (`inboxCount`).
 */
export function getMyClubsActionCounts(): Promise<PendingApplicationsCountDto> {
  return apiClient.get<PendingApplicationsCountDto>(
    '/api/users/me/applications-pending-count',
  );
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
