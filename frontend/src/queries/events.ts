import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  castVote,
  confirmParticipation,
  createEvent,
  declineParticipation,
  disputeAttendance,
  getClubEvents,
  getEvent,
  getEventResponders,
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
      // Invalidate every params variant of this club's event list (filters/pagination).
      qc.invalidateQueries({ queryKey: queryKeys.events.byClubAll(clubId) });
      // Unified activity feed must refresh too — newly created event must
      // appear at the top across all filter variants of the manage tab.
      qc.invalidateQueries({ queryKey: queryKeys.activities.byClubAll(clubId) });
      // Global /me/events feed (Активности → События) must show the new event
      // after create — CreateEventPage navigates here, mirror useCreateSkladchina.
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

// Disputing/resolving changes the per-responder attendance. The responders query key extends
// the detail key with a `responders` suffix, and TanStack invalidates by prefix — so
// invalidating the detail key also refreshes the responders list (same trick as queryKeys.ts).
export function useDisputeAttendanceMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => disputeAttendance(eventId),
    onSuccess: (_data, eventId) => {
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
