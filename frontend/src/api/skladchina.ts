import { apiClient } from './apiClient';
import type {
  ActionRequiredCountDto,
  CreateSkladchinaRequest,
  MySkladchinaListItemDto,
  PageResponse,
  SkladchinaDetailDto,
} from '../types/api';

export function getSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.get<SkladchinaDetailDto>(`/api/skladchinas/${id}`);
}

export function getMySkladchinas(
  params?: { page?: number; size?: number },
): Promise<PageResponse<MySkladchinaListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<MySkladchinaListItemDto>>(`/api/users/me/skladchinas`, queryParams);
}

export function getClubActiveSkladchinas(clubId: string): Promise<MySkladchinaListItemDto[]> {
  return apiClient.get<MySkladchinaListItemDto[]>(`/api/clubs/${clubId}/skladchinas/active`);
}

export function getSkladchinaActionRequiredCount(): Promise<ActionRequiredCountDto> {
  return apiClient.get<ActionRequiredCountDto>('/api/users/me/skladchinas/action-required-count');
}

export function createSkladchina(
  clubId: string,
  body: CreateSkladchinaRequest,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/clubs/${clubId}/skladchinas`, body);
}

export function markPaidSkladchina(
  id: string,
  declaredAmountKopecks?: number | null,
): Promise<SkladchinaDetailDto> {
  // A-1: fixed modes send no amount (the server records the assigned share);
  // voluntary sends the user-declared amount.
  const body = declaredAmountKopecks != null ? { declaredAmountKopecks } : {};
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/mark-paid`, body);
}

export function declineSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/decline`);
}

// V28: participant opens a decline request with a reason (REQUIRES_APPROVAL templates).
export function requestDeclineSkladchina(id: string, reason: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/request-decline`, { reason });
}

// V28: organizer approves/rejects a participant's decline request.
export function resolveDeclineSkladchina(id: string, userId: string, approve: boolean): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/resolve-decline`, { approve });
}

export function closeSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/close`);
}

// A-2: organizer marks a participant paid ("получил наличкой") — fixed modes only.
export function organizerMarkPaidParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/mark-paid`);
}

// A-2 (toggle): organizer reverts a participant's payment back to pending.
export function organizerUnmarkParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/unmark`);
}

// A-3: organizer redistributes the remaining deficit across pending participants.
export function redistributeSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/redistribute`);
}
