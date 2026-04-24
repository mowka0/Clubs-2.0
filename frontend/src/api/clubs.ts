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

export interface UpdateClubBody {
  name?: string;
  description?: string;
  city?: string;
  district?: string;
  memberLimit?: number;
  subscriptionPrice?: number;
  avatarUrl?: string | null;
  rules?: string | null;
  applicationQuestion?: string | null;
}

export function updateClub(id: string, body: UpdateClubBody): Promise<ClubDetailDto> {
  return apiClient.put<ClubDetailDto>(`/api/clubs/${id}`, body);
}

export function deleteClub(id: string): Promise<void> {
  return apiClient.delete<void>(`/api/clubs/${id}`);
}

export async function uploadImage(file: File): Promise<string> {
  const { url } = await apiClient.uploadFile('/api/upload', file);
  return url;
}
