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
  getOrganizerCard,
  updateClub,
} from '../api/clubs';
import type { ClubFilters, CreateClubBody, UpdateClubBody } from '../api/clubs';
import {
  applyToClub,
  getLeavePreview,
  joinByInviteCode,
  joinClub,
  leaveClub,
} from '../api/membership';
import { queryKeys } from './queryKeys';

// Размер страницы по умолчанию для бесконечного списка клубов.
const PAGE_SIZE = '20';

/**
 * Бесконечный список публичных клубов для DiscoveryPage.
 * `pageParam` — индекс страницы с 0; `getNextPageParam` возвращает undefined,
 * когда страниц больше нет, тогда `hasNextPage` становится false.
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

/** Карточка доверия организатора для шита оплаты взносов. `enabled` завязан на открытие шита. */
export function useOrganizerCardQuery(clubId: string | undefined, options: { enabled?: boolean } = {}) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.organizerCard(clubId ?? ''),
    queryFn: () => getOrganizerCard(clubId!),
    enabled,
    staleTime: 60_000,
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

/**
 * Выход из клуба. Бэкенд-каскад для бесплатных клубов удаляет RSVP вызывающего
 * в активных событиях и строки участия в активных складчинах, плюс любую
 * pending/approved заявку. Для платных клубов переключаются только заявка и
 * сама подписка — RSVP/складчины остаются валидными до истечения.
 * Инвалидируем агрессивно, чтобы MyClubsPage, ClubPage, ленты и кэши деталей
 * по каждой складчине/событию перезапросили данные, а не показывали, что
 * юзер всё ещё в сборе.
 */
export function useLeaveClubMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (clubId: string) => leaveClub(clubId),
    onSuccess: (_data, clubId) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      qc.invalidateQueries({ queryKey: queryKeys.events.all });
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.all });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.byClubActive(clubId) });
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}

/**
 * Превью обязательств перед выходом, подгружается лениво при открытии диалога выхода
 * (передавайте `enabled` = диалог-открыт И бесплатный клуб — платные клубы ничего не ломают).
 * Всегда свежее: обязательства меняются по мере голосования/оплат юзера, поэтому `staleTime: 0`.
 */
export function useLeavePreviewQuery(clubId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.clubs.leavePreview(clubId ?? ''),
    queryFn: () => getLeavePreview(clubId!),
    enabled: Boolean(clubId) && enabled,
    staleTime: 0,
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
