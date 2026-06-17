import { useQuery } from '@tanstack/react-query';
import { getClubQuality } from '../api/clubQuality';
import { queryKeys } from './queryKeys';

export function useClubQualityQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.quality(clubId ?? ''),
    queryFn: () => getClubQuality(clubId!),
    enabled: Boolean(clubId),
  });
}
