import { apiClient } from './apiClient';
import type { EventDetailDto, EventListItemDto, EventResponderDto, MyAttendanceDto, MyEventListItemDto, PageResponse } from '../types/api';

export interface CreateEventBody {
  title: string;
  description?: string;
  locationText: string;
  // Гео-точка места обязательна (fail-closed, решение PO): бэкенд отвергает событие без
  // координат. locationText — адрес из обратного геокодера по выбранной точке.
  locationLat: number;
  locationLon: number;
  // Опциональное уточнение к месту (≤200 символов), отдельное от адреса.
  locationHint?: string;
  eventDatetime: string;
  participantLimit: number;
  votingOpensDaysBefore?: number;
  photoUrl?: string;
}

export function getClubEvents(
  clubId: string,
  params?: { status?: string; page?: string; size?: string }
): Promise<PageResponse<EventListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params) {
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined) queryParams[k] = v; });
  }
  return apiClient.get<PageResponse<EventListItemDto>>(`/api/clubs/${clubId}/events`, queryParams);
}

export function getMyEvents(
  params?: { page?: number; size?: number }
): Promise<PageResponse<MyEventListItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<MyEventListItemDto>>(`/api/users/me/events`, queryParams);
}

export function getEvent(id: string): Promise<EventDetailDto> {
  return apiClient.get<EventDetailDto>(`/api/events/${id}`);
}

export function createEvent(clubId: string, body: CreateEventBody): Promise<EventDetailDto> {
  return apiClient.post<EventDetailDto>(`/api/clubs/${clubId}/events`, body);
}

/** F5-14: организатор отменяет ещё не начавшееся событие, с опциональной причиной (≤500 символов). */
export function cancelEvent(eventId: string, reason?: string): Promise<EventDetailDto> {
  return apiClient.post<EventDetailDto>(`/api/events/${eventId}/cancel`, reason ? { reason } : undefined);
}

export function castVote(eventId: string, vote: string): Promise<{ eventId: string; vote: string; goingCount: number; maybeCount: number; notGoingCount: number }> {
  return apiClient.post(`/api/events/${eventId}/vote`, { vote });
}

export function getMyVote(eventId: string): Promise<{ vote: string | null }> {
  return apiClient.get(`/api/events/${eventId}/my-vote`);
}

export function getEventResponders(eventId: string): Promise<EventResponderDto[]> {
  return apiClient.get(`/api/events/${eventId}/responses`);
}

/**
 * F5-04: собственный статус посещения вызывающего. В отличие от /responses, доступ НЕ ограничен
 * членством в клубе, так что участник, покинувший клуб, всё ещё может открыть UI оспаривания.
 * 404, если у вызывающего нет строки response (организатор / не участник).
 */
export function getMyAttendance(eventId: string): Promise<MyAttendanceDto> {
  return apiClient.get(`/api/events/${eventId}/my-attendance`);
}

export function confirmParticipation(eventId: string): Promise<{ eventId: string; status: string; confirmedCount: number; participantLimit: number }> {
  return apiClient.post(`/api/events/${eventId}/confirm`);
}

export function declineParticipation(eventId: string): Promise<{ eventId: string; status: string; confirmedCount: number; participantLimit: number }> {
  return apiClient.post(`/api/events/${eventId}/decline`);
}

export function markAttendance(eventId: string, attendance: { userId: string; attended: boolean }[]): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/attendance`, { attendance });
}

/** Участник оспаривает отметку «отсутствовал» (ATT-3), с опциональной заметкой. Бэкенд: absent → disputed. */
export function disputeAttendance(eventId: string, note?: string): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/dispute`, note ? { note } : undefined);
}

/** Организатор разрешает спорную отметку в attended/absent. */
export function resolveDispute(eventId: string, userId: string, attended: boolean): Promise<{ eventId: string; markedCount: number }> {
  return apiClient.post(`/api/events/${eventId}/attendance/${userId}/resolve`, { attended });
}

export function getFinances(clubId: string): Promise<import('../types/api').FinancesDto> {
  return apiClient.get(`/api/clubs/${clubId}/finances`);
}
