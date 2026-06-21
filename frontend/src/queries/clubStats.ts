import { useQuery } from '@tanstack/react-query';
import { getChurnedMembers, getClubStats } from '../api/clubStats';
import { queryKeys } from './queryKeys';

export function useClubStatsQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.stats(clubId ?? ''),
    queryFn: () => getClubStats(clubId!),
    enabled: Boolean(clubId),
  });
}

/** Win-back roster — fetched lazily (only when the nudge is expanded) via [enabled]. */
export function useChurnedMembersQuery(clubId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.clubs.churnedMembers(clubId ?? ''),
    queryFn: () => getChurnedMembers(clubId!),
    enabled: Boolean(clubId) && enabled,
  });
}
