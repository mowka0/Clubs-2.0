import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  completeFreeMembership,
  getMyApplications,
  getMyClubsActionCounts,
  getMyPendingApplications,
  rejectApplication,
} from '../api/membership';
import { queryKeys } from './queryKeys';

export function useMyApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.mine(),
    queryFn: getMyApplications,
  });
}

/**
 * All pending applications across clubs the caller owns — used by the
 * cross-club organizer inbox on MyClubsPage. Mirrors the lightweight
 * count query below.
 */
export function useMyPendingApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPending,
    queryFn: getMyPendingApplications,
    staleTime: 60_000,
  });
}

/**
 * Counter feeding the «Мои клубы» tab-dot: organizer-side pending applications
 * (`{ inboxCount }`). One backend call, one cache slot, mirrors
 * useSkladchinaActionRequiredCountQuery.
 */
export function useMyClubsActionCountsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPendingActionCounts,
    queryFn: getMyClubsActionCounts,
    staleTime: 60_000,
  });
}

interface ApproveApplicationArgs {
  applicationId: string;
  clubId: string;
}

export function useApproveApplicationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId }: ApproveApplicationArgs) => approveApplication(applicationId),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.applications(clubId) });
      qc.invalidateQueries({
        queryKey: queryKeys.clubs.applications(clubId, 'pending'),
      });
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPending });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      // Approving a paid-club application now creates the membership directly (in `frozen`),
      // so the per-club member list (organizer dashboard) refreshes to show the new row.
      qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
    },
  });
}

interface RejectApplicationArgs {
  applicationId: string;
  clubId: string;
  reason: string;
}

export function useRejectApplicationMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId, reason }: RejectApplicationArgs) =>
      rejectApplication(applicationId, reason),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.clubs.applications(clubId) });
      qc.invalidateQueries({
        queryKey: queryKeys.clubs.applications(clubId, 'pending'),
      });
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPending });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
    },
  });
}

interface CompleteFreeMembershipArgs {
  applicationId: string;
  clubId: string;
}

/**
 * Finalises a stuck free-club approved application by creating the missing
 * membership. After success: refetch caller's clubs (the new membership
 * appears), the club detail (memberCount bumped), and every applications
 * cache so the stuck CTA disappears.
 */
export function useCompleteFreeMembershipMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ applicationId }: CompleteFreeMembershipArgs) =>
      completeFreeMembership(applicationId),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.applications.mine() });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
    },
  });
}
