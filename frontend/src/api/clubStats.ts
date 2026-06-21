import { apiClient } from './apiClient';
import type { ClubStatsDto } from '../types/api';

/** Owner-only club statistics (place subject, anchor club_id). See docs/modules/club-quality.md §9. */
export function getClubStats(clubId: string): Promise<ClubStatsDto> {
  return apiClient.get<ClubStatsDto>(`/api/clubs/${clubId}/stats`);
}
