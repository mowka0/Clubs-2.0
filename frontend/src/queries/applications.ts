import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  getClubApplications,
  getMyApplications,
  getMyPendingApplications,
  getMyPendingApplicationsCount,
  rejectApplication,
} from '../api/membership';
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
 * Count of pending applications for the caller — powers the bottom-nav
 * tab-dot on «Мои клубы». Kept light (one COUNT on the backend) and
 * cached for 60s, mirroring useSkladchinaActionRequiredCountQuery.
 */
export function useMyPendingApplicationsCountQuery() {
  return useQuery({
    queryKey: queryKeys.applications.myPendingCount,
    queryFn: getMyPendingApplicationsCount,
    select: (data) => data.count,
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
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingCount });
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
      qc.invalidateQueries({ queryKey: queryKeys.applications.myPendingCount });
    },
  });
}
