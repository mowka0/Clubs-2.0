import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  closeSkladchina,
  createSkladchina,
  declineSkladchina,
  getEventSplitState,
  getMySkladchinas,
  getSkladchina,
  getSkladchinaActionRequiredCount,
  markPaidSkladchina,
  organizerMarkPaidParticipant,
  organizerUnmarkParticipant,
  requestDeclineSkladchina,
  resolveDeclineSkladchina,
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

/**
 * Count of active skladchinas the user must still pay. Powers the "Сборы" tab
 * badge + the bottom-nav "Активности" dot so unpaid obligations stay visible
 * from anywhere. Kept lightweight (one COUNT) and runs wherever the nav lives.
 */
export function useSkladchinaActionRequiredCountQuery() {
  return useQuery({
    queryKey: queryKeys.skladchinas.actionRequiredCount,
    queryFn: getSkladchinaActionRequiredCount,
    select: (data) => data.count,
    staleTime: 60_000,
  });
}

export function useSkladchinaQuery(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.detail(id ?? ''),
    queryFn: () => getSkladchina(id!),
    enabled: Boolean(id),
  });
}

/** Existing split for an event — drives the EventPage "Разделить счёт" button. */
export function useEventSplitStateQuery(eventId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.skladchinas.eventState(eventId ?? ''),
    queryFn: () => getEventSplitState(eventId!),
    enabled: Boolean(eventId),
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
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
      // Unified activity feed must refresh too — newly created skladchina
      // must appear at the top across all filter variants of the manage tab.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}

interface MarkPaidArgs {
  id: string;
  // A-1: omitted for fixed modes (server records the share); the declared amount for voluntary.
  declaredAmountKopecks?: number | null;
}

export function useMarkPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, declaredAmountKopecks }: MarkPaidArgs) =>
      markPaidSkladchina(id, declaredAmountKopecks),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
    },
  });
}

interface OrganizerParticipantArgs {
  id: string;
  userId: string;
}

/** A-2: organizer marks a participant paid / reverts it. Shared invalidation for both. */
function invalidateAfterOrganizerAction(qc: ReturnType<typeof useQueryClient>, id: string) {
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
  // The marked participant's "action required" obligation changed — refresh the badge count.
  qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
}

export function useOrganizerMarkPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId }: OrganizerParticipantArgs) => organizerMarkPaidParticipant(id, userId),
    onSuccess: (_data, { id }) => invalidateAfterOrganizerAction(qc, id),
  });
}

export function useOrganizerUnmarkMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId }: OrganizerParticipantArgs) => organizerUnmarkParticipant(id, userId),
    onSuccess: (_data, { id }) => invalidateAfterOrganizerAction(qc, id),
  });
}

/** V28: participant requests to decline a bill with a reason (split_bill). */
export function useRequestDeclineMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => requestDeclineSkladchina(id, reason),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
    },
  });
}

/** V28/V29: organizer approves/rejects a participant's decline request (reject needs a reason). */
export function useResolveDeclineMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, userId, approve, rejectReason }: { id: string; userId: string; approve: boolean; rejectReason?: string }) =>
      resolveDeclineSkladchina(id, userId, approve, rejectReason),
    onSuccess: (_data, { id }) => {
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.detail(id) });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.myFeed });
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
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
      qc.invalidateQueries({ queryKey: queryKeys.skladchinas.actionRequiredCount });
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
