import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import {
  claimDues,
  freezeMember,
  getAwardSuggestions,
  getClubMembers,
  getMemberAttention,
  getMemberProfile,
  getMyGamification,
  getMyReputation,
  getOrganizerAwaitingDues,
  grantMemberAward,
  markMemberDuesPaid,
  rejectMember,
  revokeMemberAward,
  setMemberAccessUntil,
  unfreezeMember,
  unmarkMemberDues,
  updateMemberNote,
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

/** Red-dot feed: soon-expiring + frozen-awaiting-dues counts. Owner-only — gate with
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

/** Cross-club «Ждут оплаты»: frozen members across the caller's owned clubs. Returns [] for
 *  non-owners (server filters by ownership) — gate `enabled` on owning ≥1 club to skip the round-trip. */
export function useOrganizerAwaitingDuesQuery(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.organizer.awaitingDues,
    queryFn: getOrganizerAwaitingDues,
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  });
}

/** After any access-gate action the member list (badges/buckets), the per-club red-dot count, and
 *  the cross-club «Ждут оплаты» feed all change. */
function invalidateAfterMemberGateAction(qc: QueryClient, clubId: string) {
  qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
  qc.invalidateQueries({ queryKey: queryKeys.clubs.memberAttention(clubId) });
  qc.invalidateQueries({ queryKey: queryKeys.organizer.awaitingDues });
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

/** B+C: reject a paid join (refund offline) — removes the frozen member. Same invalidations as the
 *  other gate actions (buckets + red-dot + cross-club «Ждут оплаты»). */
export function useRejectMemberMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, reason }: MemberGateArgs & { reason?: string | null }) =>
      rejectMember(clubId, userId, reason),
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

/** Member admin S1: set a custom access-window end («своя дата»). Touches access → invalidate buckets +
 *  the member profile card (its «Подписка до …» line). */
export function useSetMemberAccessUntilMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, until }: MemberGateArgs & { until: string }) =>
      setMemberAccessUntil(clubId, userId, until),
    onSuccess: (_data, { clubId, userId }) => {
      invalidateAfterMemberGateAction(qc, clubId);
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Member admin S1: set/clear the private organizer note. Only the profile card carries it. */
export function useUpdateMemberNoteMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, note }: MemberGateArgs & { note: string | null }) =>
      updateMemberNote(clubId, userId, note),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Member admin S2: grant a club-local award. Refreshes the member card (its chips) + the club's
 *  award-suggestion pool (a fresh label becomes a future autocomplete option). */
export function useGrantMemberAwardMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, emoji, label }: MemberGateArgs & { emoji: string; label: string }) =>
      grantMemberAward(clubId, userId, emoji, label),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.awardSuggestions(clubId) });
    },
  });
}

/** Member admin S2: remove a club-local award. Only the member card carries it. */
export function useRevokeMemberAwardMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, userId, awardId }: MemberGateArgs & { awardId: string }) =>
      revokeMemberAward(clubId, userId, awardId),
    onSuccess: (_data, { clubId, userId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.memberProfile(clubId, userId) });
    },
  });
}

/** Autocomplete pool for the grant form. Organizer-only — gate `enabled` on the edit form being open
 *  so member/visitor contexts never hit a guaranteed-403. */
export function useAwardSuggestionsQuery(
  clubId: string | undefined,
  options: { enabled?: boolean } = {},
) {
  const enabled = Boolean(clubId) && (options.enabled ?? true);
  return useQuery({
    queryKey: queryKeys.clubs.awardSuggestions(clubId ?? ''),
    queryFn: () => getAwardSuggestions(clubId!),
    enabled,
    staleTime: 60_000,
  });
}

/** Member declares they paid the dues (sbp + screenshot URL, or cash). Refreshes «Мои клубы» so the
 *  frozen club screen flips to «оплата на проверке». */
export function useClaimDuesMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, method, proofUrl }: { clubId: string; method: 'sbp' | 'cash'; proofUrl?: string | null }) =>
      claimDues(clubId, method, proofUrl),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
    },
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
