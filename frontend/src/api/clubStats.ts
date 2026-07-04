import { apiClient } from './apiClient';
import type { ChurnedMemberDto, ClubStatsDto } from '../types/api';

/** Статистика клуба только для владельца (subject = место, anchor = club_id). См. docs/modules/club-quality.md §9. */
export function getClubStats(clubId: string): Promise<ClubStatsDto> {
  return apiClient.get<ClubStatsDto>(`/api/clubs/${clubId}/stats`);
}

/** Ростер для возврата участников только для владельца (участники за напоминанием «Верните N ушедших»). */
export function getChurnedMembers(clubId: string): Promise<ChurnedMemberDto[]> {
  return apiClient.get<ChurnedMemberDto[]>(`/api/clubs/${clubId}/churned-members`);
}
