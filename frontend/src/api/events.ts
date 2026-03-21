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
