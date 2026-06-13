import { useQuery } from '@tanstack/react-query';
import {
  getClubAwaitingPaymentApplicants,
  getClubMembers,
  getMemberProfile,
  getMyReputation,
} from '../api/membership';
import type { GetClubMembersOptions } from '../api/membership';
import { queryKeys } from './queryKeys';

/**
 * Members of [clubId]. When `options.includeCancelled` is true, the server
 * also returns paid members who cancelled autorenew but are still inside the
 * paid period (`subscriptionCancelled=true` on the DTO). The two variants
 * cache under different keys so the legacy active-only callers don't pick
 * up disabled rows. See docs/modules/club-leave.md.
 */
export function useClubMembersQuery(
  clubId: string | undefined,
  options: GetClubMembersOptions = {},
) {
  const includeCancelled = Boolean(options.includeCancelled);
  return useQuery({
    queryKey: includeCancelled
      ? queryKeys.clubs.membersWith(clubId ?? '', { includeCancelled: true })
      : queryKeys.clubs.members(clubId ?? ''),
    queryFn: () => getClubMembers(clubId!, { includeCancelled }),
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

/** Authenticated user's reputation overview: the global "N из M" aggregate + per-club Trust,
 *  split into active clubs and "История" (left clubs that still carry a track record). */
export function useMyReputationQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myReputation(),
    queryFn: getMyReputation,
  });
}
