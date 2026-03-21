import { apiClient } from './apiClient';
import type { EventDetailDto, EventListItemDto, PageResponse } from '../types/api';

export interface CreateEventBody {
  title: string;
  description?: string;
  locationText: string;
  eventDatetime: string;
  participantLimit: number;
  votingOpensDaysBefore?: number;
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

export function getEvent(id: string): Promise<EventDetailDto> {
  return apiClient.get<EventDetailDto>(`/api/events/${id}`);
}

export function createEvent(clubId: string, body: CreateEventBody): Promise<EventDetailDto> {
  return apiClient.post<EventDetailDto>(`/api/clubs/${clubId}/events`, body);
}

export function castVote(eventId: string, vote: string): Promise<{ eventId: string; vote: string; goingCount: number; maybeCount: number; notGoingCount: number }> {
  return apiClient.post(`/api/events/${eventId}/vote`, { vote });
}

export function getMyVote(eventId: string): Promise<{ vote: string | null }> {
  return apiClient.get(`/api/events/${eventId}/my-vote`);
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

export function getFinances(clubId: string): Promise<import('../types/api').FinancesDto> {
  return apiClient.get(`/api/clubs/${clubId}/finances`);
}
