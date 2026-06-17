import { apiClient } from './apiClient';
import type { ClubFactsDto } from '../types/api';

/** L1 club-quality facts (place subject, anchor club_id). See docs/modules/club-quality.md. */
export function getClubQuality(clubId: string): Promise<ClubFactsDto> {
  return apiClient.get<ClubFactsDto>(`/api/clubs/${clubId}/quality`);
}
