import { apiClient } from './apiClient';
import type { MembershipDto } from '../types/api';

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
