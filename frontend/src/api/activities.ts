import { apiClient } from './apiClient';
import type { PageResponse } from '../types/api';

export type ActivityType = 'event' | 'skladchina';
export type ActivityFilter = 'all' | ActivityType;

interface ActivityBase {
  type: ActivityType;
  id: string;
  clubId: string;
  title: string;
  createdAt: string;
  isCompleted: boolean;
}

export type EventActivityStatus =
  | 'upcoming'
  | 'stage_1'
  | 'stage_2'
  | 'completed'
  | 'cancelled';

export interface EventActivityDto extends ActivityBase {
  type: 'event';
  eventDatetime: string;
  locationText: string;
  participantLimit: number;
  goingCount: number;
  status: EventActivityStatus;
  descriptionPreview: string | null;
}

export type SkladchinaActivityStatus =
  | 'active'
  | 'closed_success'
  | 'closed_failed'
  | 'cancelled';

export type SkladchinaActivityMode =
  | 'fixed_equal'
  | 'fixed_individual'
  | 'voluntary';

export interface SkladchinaActivityDto extends ActivityBase {
  type: 'skladchina';
  paymentMode: SkladchinaActivityMode;
  totalGoalKopecks: number | null;
  collectedKopecks: number;
  deadline: string;
  participantCount: number;
  paidCount: number;
  status: SkladchinaActivityStatus;
  affectsReputation: boolean;
}

export type ActivityItemDto = EventActivityDto | SkladchinaActivityDto;

export interface ClubActivitiesParams {
  type?: ActivityType;
  includeCompleted?: boolean;
  page?: number;
  size?: number;
}

export function getClubActivities(
  clubId: string,
  params?: ClubActivitiesParams,
): Promise<PageResponse<ActivityItemDto>> {
  const queryParams: Record<string, string> = {};
  if (params?.type !== undefined) queryParams.type = params.type;
  if (params?.includeCompleted !== undefined) {
    queryParams.includeCompleted = String(params.includeCompleted);
  }
  if (params?.page !== undefined) queryParams.page = String(params.page);
  if (params?.size !== undefined) queryParams.size = String(params.size);
  return apiClient.get<PageResponse<ActivityItemDto>>(
    `/api/clubs/${clubId}/activities`,
    queryParams,
  );
}
