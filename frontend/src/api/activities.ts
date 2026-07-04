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
  // Размер подтверждённого ростера Этапа 2. Карточка показывает `goingCount` во время stage 1 и
  // переключается на `confirmedCount`, когда голосование закрывается (stage_2/completed), — так же,
  // как страница события (F5-21).
  confirmedCount: number;
  status: EventActivityStatus;
  descriptionPreview: string | null;
  photoUrl: string | null;
  /** Событие ждёт голоса текущего пользователя на этапе 1 или подтверждения на этапе 2. */
  actionRequired: boolean;
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
 * Фид, разделённый по статусу завершённости. Бэкенд уже сортирует каждый массив:
 * `upcoming` — ближайшие сначала (по дате события / дедлайну), `past` — сначала самые недавние.
 * Фронтенд рендерит оба в полученном порядке — без пересортировки на клиенте.
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
