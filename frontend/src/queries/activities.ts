import { useInfiniteQuery } from '@tanstack/react-query';
import { getClubActivities, type ActivityType } from '../api/activities';
import { queryKeys } from './queryKeys';

const PAGE_SIZE = 20;

export interface UseClubActivitiesParams {
  type?: ActivityType;
  includeCompleted?: boolean;
}

export function useClubActivitiesQuery(
  clubId: string | undefined,
  params?: UseClubActivitiesParams,
) {
  return useInfiniteQuery({
    queryKey: queryKeys.activities.byClub(clubId ?? '', params),
    queryFn: ({ pageParam }) =>
      getClubActivities(clubId!, {
        ...params,
        page: pageParam,
        size: PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (last) =>
      last.page + 1 < last.totalPages ? last.page + 1 : undefined,
    enabled: Boolean(clubId),
  });
}
