import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelEvent,
  castVote,
  confirmParticipation,
  createEvent,
  declineParticipation,
  disputeAttendance,
  getClubEvents,
  getEvent,
  getEventResponders,
  getMyAttendance,
  getMyEvents,
  getMyVote,
  markAttendance,
  resolveDispute,
} from '../api/events';
import type { CreateEventBody } from '../api/events';
import { queryKeys, type EventListParams } from './queryKeys';

export function useClubEventsQuery(clubId: string | undefined, params?: EventListParams) {
  return useQuery({
    queryKey: queryKeys.events.byClub(clubId ?? '', params),
    queryFn: () => getClubEvents(clubId!, params),
    enabled: Boolean(clubId),
  });
}

export function useEventQuery(eventId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.events.detail(eventId ?? ''),
    queryFn: () => getEvent(eventId!),
    enabled: Boolean(eventId),
  });
}

export function useMyVoteQuery(eventId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: queryKeys.events.myVote(eventId ?? ''),
    queryFn: () => getMyVote(eventId!),
    enabled: Boolean(eventId) && enabled,
  });
}

export function useEventRespondersQuery(eventId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...queryKeys.events.detail(eventId ?? ''), 'responders'],
    queryFn: () => getEventResponders(eventId!),
    enabled: Boolean(eventId) && enabled,
  });
}

/**
 * F5-04: собственное состояние посещаемости вызывающего — управляет контролами оспаривания участника
 * даже для того, кто уже покинул клуб (member-gated запрос responders для него вернёт 403). Ключ
 * расширяет detail-ключ, поэтому мутации dispute/resolve/mark (инвалидирующие detail) обновляют
 * его по префиксу. 404 (организатор / не-участник) — ожидаемый исход, без ретрая.
 */
export function useMyAttendanceQuery(eventId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...queryKeys.events.detail(eventId ?? ''), 'my-attendance'],
    queryFn: () => getMyAttendance(eventId!),
    enabled: Boolean(eventId) && enabled,
    retry: false,
  });
}

const MY_EVENTS_PAGE_SIZE = 20;

export function useMyEventsQuery() {
  return useInfiniteQuery({
    queryKey: queryKeys.events.myFeed,
    queryFn: ({ pageParam }) => getMyEvents({ page: pageParam, size: MY_EVENTS_PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
}

interface CastVoteArgs {
  eventId: string;
  vote: 'going' | 'maybe' | 'not_going';
}

export function useCastVoteMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, vote }: CastVoteArgs) => castVote(eventId, vote),
    onSuccess: (_data, { eventId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
    },
  });
}

export function useConfirmParticipationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => confirmParticipation(eventId),
    onSuccess: (_data, eventId) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
    },
  });
}

export function useDeclineParticipationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => declineParticipation(eventId),
    onSuccess: (_data, eventId) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
    },
  });
}

interface CreateEventArgs {
  clubId: string;
  body: CreateEventBody;
}

export function useCreateEventMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, body }: CreateEventArgs) => createEvent(clubId, body),
    onSuccess: (_data, { clubId }) => {
      // Инвалидируем все варианты параметров списка событий этого клуба (фильтры/пагинация).
      qc.invalidateQueries({ queryKey: queryKeys.events.byClubAll(clubId) });
      // Единая лента активностей тоже должна обновиться — новое событие должно
      // появиться сверху во всех вариантах фильтров вкладки управления.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
      // Глобальная лента /me/events (Активности → События) должна показать новое событие
      // после создания — CreateEventPage переходит сюда, зеркалит useCreateSkladchina.
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
    },
  });
}

interface MarkAttendanceArgs {
  eventId: string;
  attendance: { userId: string; attended: boolean }[];
}

export function useMarkAttendanceMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, attendance }: MarkAttendanceArgs) =>
      markAttendance(eventId, attendance),
    onSuccess: (_data, { eventId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
    },
  });
}

// Оспаривание/разрешение спора меняет посещаемость конкретного респондента. Ключ запроса responders
// расширяет detail-ключ суффиксом `responders`, а TanStack инвалидирует по префиксу — поэтому
// инвалидация detail-ключа обновляет и список responders (тот же трюк, что в queryKeys.ts).
interface DisputeArgs {
  eventId: string;
  note?: string;
}

export function useDisputeAttendanceMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, note }: DisputeArgs) => disputeAttendance(eventId, note),
    onSuccess: (_data, { eventId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
    },
  });
}

interface ResolveDisputeArgs {
  eventId: string;
  userId: string;
  attended: boolean;
}

export function useResolveDisputeMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, userId, attended }: ResolveDisputeArgs) =>
      resolveDispute(eventId, userId, attended),
    onSuccess: (_data, { eventId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
    },
  });
}


interface CancelEventArgs {
  eventId: string;
  clubId: string;
  reason?: string;
}

export function useCancelEventMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ eventId, reason }: CancelEventArgs) => cancelEvent(eventId, reason),
    onSuccess: (_data, { eventId, clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      qc.invalidateQueries({ queryKey: queryKeys.events.myFeed });
      // Лента активностей клуба тоже отображает состояние «отменено».
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
    },
  });
}
