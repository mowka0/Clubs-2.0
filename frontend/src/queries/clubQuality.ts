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
 * Факты для карточек Discovery в бесконечном списке — грузятся ОДНИМ БАТЧЕМ НА СТРАНИЦУ → `Map`
 * с ключом clubId для O(1)-поиска карточки. Именно постранично (а не по накопленному набору),
 * чтобы каждая страница кэшировалась независимо: прокрутка никогда не перезапрашивает предыдущие
 * страницы, и ни одна страница не превышает серверный лимит батча. Результат объединяет все
 * загруженные страницы; клубы, чья страница ещё грузится, просто отсутствуют (карточка деградирует
 * до имени+мета).
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
