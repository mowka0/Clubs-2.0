import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import { useMyClubsQuery } from './clubs';
import { queryKeys } from './queryKeys';
import { getClub } from '../api/clubs';
import type { ClubPickerOption } from '../components/manage/ClubPickerModal';

interface OrganizerClubsResult {
  clubs: ClubPickerOption[];
  isLoading: boolean;
}

/**
 * Clubs the current user organizes, enriched with name/avatar for the global
 * create flow. Organizer membership is the source of truth (`role === 'organizer'`);
 * club details are fetched per club via the shared detail cache.
 */
export function useOrganizerClubs(): OrganizerClubsResult {
  const myClubsQuery = useMyClubsQuery();

  const organizerClubIds = useMemo(
    () =>
      (myClubsQuery.data ?? [])
        .filter((m) => m.role === 'organizer')
        .map((m) => m.clubId),
    [myClubsQuery.data],
  );

  const detailQueries = useQueries({
    queries: organizerClubIds.map((id) => ({
      queryKey: queryKeys.clubs.detail(id),
      queryFn: () => getClub(id),
    })),
  });

  const clubs = useMemo<ClubPickerOption[]>(
    () =>
      organizerClubIds.map((id, idx) => {
        const club = detailQueries[idx]?.data;
        return {
          id,
          name: club?.name ?? `Клуб ${id.slice(0, 8)}…`,
          avatarUrl: club?.avatarUrl ?? null,
          category: club?.category ?? 'other',
        };
      }),
    [organizerClubIds, detailQueries],
  );

  return {
    clubs,
    isLoading: myClubsQuery.isPending,
  };
}
