import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import {
  freezeMember,
  getClubMembers,
  getMemberAttention,
  getMemberProfile,
  getMyGamification,
  getMyReputation,
  markMemberDuesPaid,
  unfreezeMember,
  unmarkMemberDues,
} from '../api/membership';
import { queryKeys } from './queryKeys';

/** Members of [clubId]. The organizer additionally receives frozen members + each member's
 *  access state and paid-through date (de-Stars dashboard); regular members get the active-only
 *  list with those fields null. */
export function useClubMembersQuery(clubId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.clubs.members(clubId ?? ''),
    queryFn: () => getClubMembers(clubId!),
    enabled: Boolean(clubId),
  });
}

/** Red-dot feed: how many members' paid access ends within the week. Owner-only — gate with
 *  `enabled: isOrganizer` to skip a guaranteed-403 from member/visitor contexts. */
export function useMemberAttentionQuery(
  clubId: string | undefined,
  options: { enabled?: boolean } = {},
) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.memberAttention(clubId ?? ''),
    queryFn: () => getMemberAttention(clubId!),
    enabled,
    staleTime: 60_000,
  });
}

/** After any access-gate action the member list (badges/buckets) and the red-dot count both change. */
function invalidateAfterMemberGateAction(qc: QueryClient, clubId: string) {
  qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
  qc.invalidateQueries({ queryKey: queryKeys.clubs.memberAttention(clubId) });
}

interface MemberGateArgs {
  clubId: string;
  userId: string;
}

/** «Взнос получен» — open access + extend the paid window. The primary dashboard action. */
export function useMarkMemberDuesPaidMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => markMemberDuesPaid(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** «Закрыть доступ» — active → frozen. */
export function useFreezeMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => freezeMember(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Reopen access without extending the paid window (frozen → active). */
export function useUnfreezeMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => unfreezeMember(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
  });
}

/** Clear the dues mark without touching access/window. */
export function useUnmarkMemberDuesMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId }: MemberGateArgs) => unmarkMemberDues(clubId, userId),
    onSuccess: (_data, { clubId }) => invalidateAfterMemberGateAction(qc, clubId),
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

/** Authenticated user's gamification panel (XP, level, progress, badges). Self-view only. */
export function useMyGamificationQuery() {
  return useQuery({
    queryKey: queryKeys.clubs.myGamification(),
    queryFn: getMyGamification,
  });
}
