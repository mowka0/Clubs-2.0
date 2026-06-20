import { apiClient } from './apiClient';
import type { ClubCardFactsDto, ClubFactsDto } from '../types/api';

/** L1 club-quality facts (place subject, anchor club_id). See docs/modules/club-quality.md. */
export function getClubQuality(clubId: string): Promise<ClubFactsDto> {
  return apiClient.get<ClubFactsDto>(`/api/clubs/${clubId}/quality`);
}

/** Batched Discovery-card facts for a page of clubs (one request, not per card). */
export function getClubQualityBatch(clubIds: string[]): Promise<ClubCardFactsDto[]> {
  return apiClient.get<ClubCardFactsDto[]>('/api/clubs/quality/batch', { ids: clubIds.join(',') });
}
