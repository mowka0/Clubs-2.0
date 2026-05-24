import { useQuery } from '@tanstack/react-query';
import {
  getClubActivities,
  type ActivityType,
  type ClubActivityFeed,
} from '../api/activities';
import { queryKeys } from './queryKeys';

export interface UseClubActivitiesParams {
  type?: ActivityType;
}

export function useClubActivitiesQuery(
  clubId: string | undefined,
  params?: UseClubActivitiesParams,
) {
  return useQuery<ClubActivityFeed>({
    queryKey: queryKeys.activities.byClub(clubId ?? '', params),
    queryFn: () => getClubActivities(clubId!, params),
    enabled: Boolean(clubId),
  });
}
