import { useQueries, useQuery } from '@tanstack/react-query';
import { getClubQuality, getClubQualityBatch } from '../api/clubQuality';
import { queryKeys } from './queryKeys';
import type { ClubCardFactsDto } from '../types/api';

export function useClubQualityQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.quality(clubId ?? ''),
    queryFn: () => getClubQuality(clubId!),
    enabled: Boolean(clubId),
  });
}

/**
 * Discovery-card facts for an infinite list, fetched ONE BATCH PER PAGE → a `Map` keyed by clubId
 * for O(1) card lookup. Per-page (not over the accumulated set) so each page caches independently:
 * scrolling never refetches earlier pages and no page exceeds the server's batch cap. Result merges
 * every loaded page; clubs whose page is still loading are simply absent (card degrades to name+meta).
 */
export function useClubCardFacts(pages: ReadonlyArray<{ content: ReadonlyArray<{ id: string }> }>) {
  const results = useQueries({
    queries: pages.map((page) => {
      const ids = [...new Set(page.content.map((c) => c.id))].sort();
      return {
        queryKey: queryKeys.clubs.cardFacts(ids),
        queryFn: () => getClubQualityBatch(ids),
        enabled: ids.length > 0,
      };
    }),
  });

  const byClub = new Map<string, ClubCardFactsDto>();
  for (const result of results) {
    for (const facts of result.data ?? []) byClub.set(facts.clubId, facts);
  }
  return byClub;
}
