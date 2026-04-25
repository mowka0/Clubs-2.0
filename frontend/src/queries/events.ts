import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  castVote,
  confirmParticipation,
  createEvent,
  declineParticipation,
  getClubEvents,
  getEvent,
  getMyVote,
  markAttendance,
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
      // Explicit invalidation — myVote key is a prefix-extension of detail,
      // technically covered by the first invalidate, but keeping for clarity
      // in case key shape changes.
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
    },
  });
}

export function useConfirmParticipationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => confirmParticipation(eventId),
    onSuccess: (_data, eventId) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      // Explicit invalidation — myVote key is a prefix-extension of detail,
      // technically covered by the first invalidate, but keeping for clarity
      // in case key shape changes.
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
    },
  });
}

export function useDeclineParticipationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) => declineParticipation(eventId),
    onSuccess: (_data, eventId) => {
      qc.invalidateQueries({ queryKey: queryKeys.events.detail(eventId) });
      // Explicit invalidation — myVote key is a prefix-extension of detail,
      // technically covered by the first invalidate, but keeping for clarity
      // in case key shape changes.
      qc.invalidateQueries({ queryKey: queryKeys.events.myVote(eventId) });
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
