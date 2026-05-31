import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  completeFreeMembership,
  getClubApplications,
  getMyApplications,
  getMyAwaitingPaymentApplications,
  getMyClubsActionCounts,
  getMyPendingApplications,
  getOrganizerAwaitingPaymentApplicants,
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
 * Cross-club organizer view: approved-but-unpaid applicants across all clubs
 * the caller owns. Drives the «Ожидают оплаты от заявителей» section on
 * MyClubsPage. Backend filters by ownership; non-organizers receive an empty
 * list (no 403). Same `staleTime: 60_000` as sibling sections.
 */
export function useOrganizerAwaitingPaymentQuery() {
  return useQuery({
    queryKey: queryKeys.applications.organizerAwaitingPayment,
    queryFn: getOrganizerAwaitingPaymentApplicants,
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
      // Approving a paid-club application creates a new awaiting-payment entry
      // for the organizer cross-club view and bumps the per-club section too.
      qc.invalidateQueries({ queryKey: queryKeys.applications.organizerAwaitingPayment });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.awaitingPaymentApplicants(clubId) });
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
      // Reject doesn't add an awaiting-payment entry, but keep the cross-club
      // organizer view in lockstep with the per-club one for consistency.
      qc.invalidateQueries({ queryKey: queryKeys.applications.organizerAwaitingPayment });
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
      // Resend by applicant doesn't move the entry but webhook-driven payment
      // can land between request and refetch; refresh the organizer view too.
      qc.invalidateQueries({ queryKey: queryKeys.applications.organizerAwaitingPayment });
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
      qc.invalidateQueries({ queryKey: queryKeys.applications.myAwaitingPayment });
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingActionCounts });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.my() });
      qc.invalidateQueries({ queryKey: queryKeys.clubs.detail(clubId) });
    },
  });
}
