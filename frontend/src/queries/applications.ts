import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveApplication,
  getClubApplications,
  getMyApplications,
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
      qc.invalidateQueries({ queryKey: queryKeys.clubs.members(clubId) });
    },
  });
}

interface RejectApplicationArgs {
  applicationId: string;
  clubId: string;
  reason?: string;
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
    },
  });
}
