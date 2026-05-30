import { useQuery } from '@tanstack/react-query';
import {
  getClubAwaitingPaymentApplicants,
  getClubMembers,
  getMemberProfile,
  getMyReputation,
} from '../api/membership';
import { queryKeys } from './queryKeys';

export function useClubMembersQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.members(clubId ?? ''),
    queryFn: () => getClubMembers(clubId!),
    enabled: Boolean(clubId),
  });
}

/**
 * Organizer-only: list of applicants for [clubId] whose application is
 * approved but Stars invoice unpaid (no active membership). Returns 403 from
 * backend if caller is not the club owner — pair with `enabled: isOrganizer`
 * to avoid a guaranteed-fail request from member/visitor contexts.
 *
 * `staleTime: 60_000` mirrors other low-churn organizer views — payment state
 * changes via webhook are eventually consistent here and refetch on focus
 * picks them up.
 */
export function useClubAwaitingPaymentApplicantsQuery(
  clubId: string | undefined,
  options: { enabled?: boolean } = {},
) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.awaitingPaymentApplicants(clubId ?? ''),
    queryFn: () => getClubAwaitingPaymentApplicants(clubId!),
    enabled,
    staleTime: 60_000,
  });
}

export function useMemberProfileQuery(
  clubId: string | undefined,
  userId: string | undefined,
) {
  return useQuery({
    queryKey: queryKeys.clubs.memberProfile(clubId ?? '', userId ?? ''),
    queryFn: () => getMemberProfile(clubId!, userId!),
    enabled: Boolean(clubId) && Boolean(userId),
  });
}

/** Authenticated user's reliability index in every club they belong to. */
export function useMyReputationQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myReputation(),
    queryFn: getMyReputation,
  });
}
