import { useQuery } from '@tanstack/react-query';
import { getFinances } from '../api/events';
import { queryKeys } from './queryKeys';

export function useClubFinancesQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.finances(clubId ?? ''),
    queryFn: () => getFinances(clubId!),
    enabled: Boolean(clubId),
  });
}
