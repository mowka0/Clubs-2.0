import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import {
  createClub,
  deleteClub,
  getClub,
  getClubByInvite,
  getClubs,
  getMyClubs,
  updateClub,
} from '../api/clubs';
import type { ClubFilters, CreateClubBody, UpdateClubBody } from '../api/clubs';
import { applyToClub, joinByInviteCode, joinClub } from '../api/membership';
import { queryKeys } from './queryKeys';

const PAGE_SIZE = '20';

/**
 * Infinite-scrolling list of public clubs for DiscoveryPage.
 * `pageParam` is the 0-based page index; `getNextPageParam` returns undefined
 * when there are no more pages so `hasNextPage` flips to false.
 */
export function useClubsQuery(filters: Omit<ClubFilters, 'page'>) {
  return useInfiniteQuery({
    queryKey: queryKeys.clubs.list(filters),
    queryFn: ({ pageParam }) =>
      getClubs({ ...filters, page: String(pageParam), size: filters.size ?? PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (lastPage, _allPages, lastPageParam) =>
      lastPageParam + 1 < lastPage.totalPages ? lastPageParam + 1 : undefined,
  });
}

export function useMyClubsQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.my(),
    queryFn: getMyClubs,
  });
}

export function useClubQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.detail(clubId ?? ''),
    queryFn: () => getClub(clubId!),
    enabled: Boolean(clubId),
  });
}

export function useClubByInviteQuery(code: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.byInvite(code ?? ''),
    queryFn: () => getClubByInvite(code!),
    enabled: Boolean(code),
  });
}

export function useCreateClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateClubBody) => createClub(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.all });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}

interface UpdateClubArgs {
  id: string;
  body: UpdateClubBody;
}

export function useUpdateClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: UpdateClubArgs) => updateClub(id, body),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.all });
    },
  });
}

export function useDeleteClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteClub(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.all });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}

export function useJoinClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (clubId: string) => joinClub(clubId),
    onSuccess: (_data, clubId) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}

interface ApplyToClubArgs {
  clubId: string;
  answerText: string;
}

export function useApplyToClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, answerText }: ApplyToClubArgs) => applyToClub(clubId, answerText),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
    },
  });
}

export function useJoinByInviteMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => joinByInviteCode(code),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
  });
}
