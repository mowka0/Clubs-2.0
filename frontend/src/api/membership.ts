import { apiClient } from './apiClient';
import type { MemberListItemDto, MemberProfileDto, MembershipDto } from '../types/api';

export interface ApplicationDto {
  id: string;
  userId: string;
  clubId: string;
  status: string;
  answerText: string | null;
  createdAt: string | null;
}

export function joinClub(clubId: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/clubs/${clubId}/join`);
}

export function applyToClub(clubId: string, answerText: string): Promise<ApplicationDto> {
  return apiClient.post<ApplicationDto>(`/api/clubs/${clubId}/apply`, { answerText });
}

export function getMyApplications(): Promise<ApplicationDto[]> {
  return apiClient.get<ApplicationDto[]>('/api/users/me/applications');
}

export function joinByInviteCode(code: string): Promise<MembershipDto> {
  return apiClient.post<MembershipDto>(`/api/invite/${code}/join`);
}

export function getClubMembers(clubId: string): Promise<MemberListItemDto[]> {
  return apiClient.get<MemberListItemDto[]>(`/api/clubs/${clubId}/members`);
}

export function getMemberProfile(clubId: string, userId: string): Promise<MemberProfileDto> {
  return apiClient.get<MemberProfileDto>(`/api/clubs/${clubId}/members/${userId}`);
}

export function getClubApplications(clubId: string): Promise<import('../types/api').ClubApplicationDto[]> {
  return apiClient.get(`/api/clubs/${clubId}/applications`);
}

export function approveApplication(applicationId: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/approve`);
}

export function rejectApplication(applicationId: string, reason?: string): Promise<void> {
  return apiClient.post(`/api/applications/${applicationId}/reject`, { reason });
}
