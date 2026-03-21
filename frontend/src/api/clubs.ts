import { apiClient } from './apiClient';
import type { ClubDetailDto, ClubListItemDto, MembershipDto, PageResponse } from '../types/api';

export interface ClubFilters {
  category?: string;
  city?: string;
  accessType?: string;
  minPrice?: string;
  maxPrice?: string;
  search?: string;
  page?: string;
  size?: string;
}

export interface CreateClubBody {
  name: string;
  description: string;
  category: string;
  accessType: string;
  city: string;
  district?: string;
  memberLimit: number;
  subscriptionPrice: number;
  avatarUrl?: string;
  rules?: string;
  applicationQuestion?: string;
}

export function getClubs(filters?: ClubFilters): Promise<PageResponse<ClubListItemDto>> {
  const params: Record<string, string> = {};
  if (filters) {
    Object.entries(filters).forEach(([k, v]) => { if (v !== undefined) params[k] = v; });
  }
  return apiClient.get<PageResponse<ClubListItemDto>>('/api/clubs', params);
}

export function getClub(id: string): Promise<ClubDetailDto> {
  return apiClient.get<ClubDetailDto>(`/api/clubs/${id}`);
}

export function createClub(body: CreateClubBody): Promise<ClubDetailDto> {
  return apiClient.post<ClubDetailDto>('/api/clubs', body);
}

export function getMyClubs(): Promise<MembershipDto[]> {
  return apiClient.get<MembershipDto[]>('/api/users/me/clubs');
}

export function getClubByInvite(code: string): Promise<ClubDetailDto> {
  return apiClient.get<ClubDetailDto>(`/api/invite/${code}`);
}
