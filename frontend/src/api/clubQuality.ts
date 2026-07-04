import { apiClient } from './apiClient';
import type { ClubCardFactsDto, ClubFactsDto } from '../types/api';

/** Факты L1 качества клуба (place subject, якорь club_id). См. docs/modules/club-quality.md. */
export function getClubQuality(clubId: string): Promise<ClubFactsDto> {
  return apiClient.get<ClubFactsDto>(`/api/clubs/${clubId}/quality`);
}

/** Факты для карточек Discovery батчем на страницу клубов (один запрос, не на каждую карточку). */
export function getClubQualityBatch(clubIds: string[]): Promise<ClubCardFactsDto[]> {
  return apiClient.get<ClubCardFactsDto[]>('/api/clubs/quality/batch', { ids: clubIds.join(',') });
}
