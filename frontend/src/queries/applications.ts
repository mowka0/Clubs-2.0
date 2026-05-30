import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  getClubApplications,
  getMyApplications,
  getMyAwaitingPaymentApplications,
  getMyClubsActionCounts,
  getMyPendingApplications,
  rejectApplication,
  resendApplicationInvoice,
} from '../api/membership';
import { useHaptic } from '../hooks/useHaptic';
import { queryKeys } from './queryKeys';

type ApplicationStatus = 'pending' | 'approved' | 'rejected';

export function useMyApplicationsQuery() {
  return useQuery({
    queryKey: queryKeys.applications.mine(),
    queryFn: getMyApplications,
  });
}

export function useClubApplicationsQuery(
  clubId: string | undefined,
  status?: ApplicationStatus,
) {
  return useQuery({
    queryKey: queryKeys.clubs.applications(clubId ?? '', status),
    queryFn: () => getClubApplications(clubId!, status),
    enabled: Boolean(clubId),
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
 * Caller's own approved-but-unpaid applications — drives the «Ожидают оплаты»
 * section on MyClubsPage. Mirrors the staleTime/cache pattern of
 * useMyPendingApplicationsQuery so both sections refresh together.
 */
export function useMyAwaitingPaymentQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myAwaitingPayment,
    queryFn: getMyAwaitingPaymentApplications,
    staleTime: 60_000,
  });
}

/**
 * Combined counter feeding the «Мои клубы» tab-dot. Returns the full
 * `{ inboxCount, awaitingPaymentCount }` shape so call-sites can show
 * either count independently; consumers that only need the union can
 * compute `inboxCount + awaitingPaymentCount` themselves.
 *
 * One backend call, one cache slot, mirrors useSkladchinaActionRequiredCountQuery.
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

/**
 * Re-send the Stars invoice for an approved-but-unpaid application. Success
 * triggers a positive haptic and refreshes the awaiting-payment list + tab-dot
 * counts (the application may have become "paid" between the user pressing
 * the button and the backend processing the invoice).
 */
export function useResendInvoiceMutation() {
  const qc = useQueryClient();
  const haptic = useHaptic();
  return useMutation({
    mutationFn: (applicationId: string) => resendApplicationInvoice(applicationId),
    onSuccess: () => {
      haptic.notify('success');
      qc.invalidateQueries({ queryKey: queryKeys.applications.myAwaitingPayment });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
    },
  });
}
