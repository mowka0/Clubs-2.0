import { apiClient } from './apiClient';
import type { ClubDetailDto, ClubListItemDto, MembershipDto, OrganizerCardDto, PageResponse } from '../types/api';

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
  // Реквизиты СБП для взносов — обязательны на бэкенде, если subscriptionPrice > 0.
  paymentLink?: string;
  paymentMethodNote?: string;
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
  // Реквизиты СБП для взносов (пустая строка очищает значение, как rules/district).
  paymentLink?: string | null;
  paymentMethodNote?: string | null;
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

/** Карточка доверия организатора для шторки оплаты взносов (de-Stars). Только по JWT / видна другим. */
export function getOrganizerCard(clubId: string): Promise<OrganizerCardDto> {
  return apiClient.get<OrganizerCardDto>(`/api/clubs/${clubId}/organizer-card`);
}
