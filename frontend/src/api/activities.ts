import { apiClient } from './apiClient';

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
  photoUrl: string | null;
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
  photoUrl: string | null;
}

export type ActivityItemDto = EventActivityDto | SkladchinaActivityDto;

/**
 * Feed split by completion status. Backend already sorts each array:
 * `upcoming` soonest-first (by event date / deadline), `past` most-recent-first.
 * The frontend renders both in received order — no client re-sorting.
 */
export interface ClubActivityFeed {
  upcoming: ActivityItemDto[];
  past: ActivityItemDto[];
}

export interface ClubActivitiesParams {
  type?: ActivityType;
}

export function getClubActivities(
  clubId: string,
  params?: ClubActivitiesParams,
): Promise<ClubActivityFeed> {
  const queryParams: Record<string, string> = {};
  if (params?.type !== undefined) queryParams.type = params.type;
  return apiClient.get<ClubActivityFeed>(
    `/api/clubs/${clubId}/activities`,
    queryParams,
  );
}
