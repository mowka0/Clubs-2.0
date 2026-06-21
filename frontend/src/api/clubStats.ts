import { apiClient } from './apiClient';
import type { ChurnedMemberDto, ClubStatsDto } from '../types/api';

/** Owner-only club statistics (place subject, anchor club_id). See docs/modules/club-quality.md §9. */
export function getClubStats(clubId: string): Promise<ClubStatsDto> {
  return apiClient.get<ClubStatsDto>(`/api/clubs/${clubId}/stats`);
}

/** Owner-only win-back roster (members behind the «Верните N ушедших» nudge). */
export function getChurnedMembers(clubId: string): Promise<ChurnedMemberDto[]> {
  return apiClient.get<ChurnedMemberDto[]>(`/api/clubs/${clubId}/churned-members`);
}
