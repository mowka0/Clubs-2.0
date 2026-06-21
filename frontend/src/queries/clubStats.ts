import { useQuery } from '@tanstack/react-query';
import { getClubStats } from '../api/clubStats';
import { queryKeys } from './queryKeys';

export function useClubStatsQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.stats(clubId ?? ''),
    queryFn: () => getClubStats(clubId!),
    enabled: Boolean(clubId),
  });
}
