import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  closeSkladchina,
  createSkladchina,
  declineSkladchina,
  getClubActiveSkladchinas,
  getMySkladchinas,
  getSkladchina,
  markPaidSkladchina,
} from '../api/skladchina';
import type { CreateSkladchinaRequest } from '../types/api';
import { queryKeys } from './queryKeys';

const PAGE_SIZE = 20;

export function useMySkladchinasQuery() {
  return useInfiniteQuery({
    queryKey: queryKeys.skladchinas.myFeed,
    queryFn: ({ pageParam }) => getMySkladchinas({ page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
}

export function useSkladchinaQuery(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.detail(id ?? ''),
    queryFn: () => getSkladchina(id!),
    enabled: Boolean(id),
  });
}

export function useClubActiveSkladchinasQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.byClubActive(clubId ?? ''),
    queryFn: () => getClubActiveSkladchinas(clubId!),
    enabled: Boolean(clubId),
  });
}

interface CreateSkladchinaArgs {
  clubId: string;
  body: CreateSkladchinaRequest;
}

export function useCreateSkladchinaMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, body }: CreateSkladchinaArgs) => createSkladchina(clubId, body),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.byClubActive(clubId) });
      // Unified activity feed must refresh too — newly created skladchina
      // must appear at the top across all filter variants of the manage tab.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}

interface MarkPaidArgs {
  id: string;
  declaredAmountKopecks: number;
}

export function useMarkPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, declaredAmountKopecks }: MarkPaidArgs) =>
      markPaidSkladchina(id, declaredAmountKopecks),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
    },
  });
}

export function useDeclineSkladchinaMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => declineSkladchina(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
    },
  });
}

export function useCloseSkladchinaMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => closeSkladchina(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.all });
    },
  });
}
