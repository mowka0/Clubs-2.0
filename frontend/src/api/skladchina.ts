import { apiClient } from './apiClient';
import type {
  ActionRequiredCountDto,
  CreateSkladchinaRequest,
  EventSplitStateDto,
  MySkladchinaListItemDto,
  PageResponse,
  SkladchinaDetailDto,
} from '../types/api';

export function getSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.get<SkladchinaDetailDto>(`/api/skladchinas/${id}`);
}

// Кнопка «Разделить счёт» на EventPage: существующий сплит события (active → open, closed_success → collected).
export function getEventSplitState(eventId: string): Promise<EventSplitStateDto> {
  return apiClient.get<EventSplitStateDto>(`/api/events/${eventId}/skladchina`);
}

export function getMySkladchinas(
  params?: { page?: number; size?: number },
): Promise<PageResponse<MySkladchinaListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<MySkladchinaListItemDto>>(`/api/users/me/skladchinas`, queryParams);
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
  // A-1: fixed-режимы сумму не шлют (сервер записывает назначенную долю);
  // voluntary шлёт сумму, заявленную пользователем.
  const body = declaredAmountKopecks != null ? { declaredAmountKopecks } : {};
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/mark-paid`, body);
}

export function declineSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/decline`);
}

// V28: участник открывает запрос на отказ с указанием причины (шаблоны REQUIRES_APPROVAL).
export function requestDeclineSkladchina(id: string, reason: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/request-decline`, { reason });
}

// V28/V29: организатор одобряет/отклоняет запрос участника на отказ. Для отклонения нужна причина.
export function resolveDeclineSkladchina(
  id: string,
  userId: string,
  approve: boolean,
  rejectReason?: string,
): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(
    `/api/skladchinas/${id}/participants/${userId}/resolve-decline`,
    { approve, rejectReason },
  );
}

export function closeSkladchina(id: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/close`);
}

// A-2: организатор отмечает участника оплатившим («получил наличкой») — только fixed-режимы.
export function organizerMarkPaidParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/mark-paid`);
}

// A-2 (toggle): организатор возвращает оплату участника обратно в pending.
export function organizerUnmarkParticipant(id: string, userId: string): Promise<SkladchinaDetailDto> {
  return apiClient.post<SkladchinaDetailDto>(`/api/skladchinas/${id}/participants/${userId}/unmark`);
}
