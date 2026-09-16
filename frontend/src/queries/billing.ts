import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getBilling, setAutopay, startCheckout } from '../api/billing';
import { queryKeys } from './queryKeys';

interface BillingQueryOptions {
  enabled?: boolean;
  /** Опрос после возврата из браузера: ResultURL может отставать от редиректа. */
  refetchInterval?: number | false;
}

export function useBillingQuery(clubId: string | undefined, options: BillingQueryOptions = {}) {
  return useQuery({
    queryKey: queryKeys.billing(clubId ?? ''),
    queryFn: () => getBilling(clubId!),
    enabled: !!clubId && options.enabled !== false,
    refetchInterval: options.refetchInterval ?? false,
  });
}

export function useStartCheckoutMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, autopay }: { clubId: string; autopay: boolean }) => startCheckout(clubId, autopay),
    onSuccess: (_data, { clubId }) => {
      qc.invalidateQueries({ queryKey: queryKeys.billing(clubId) });
    },
  });
}

export function useSetAutopayMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ clubId, autopay }: { clubId: string; autopay: boolean }) => setAutopay(clubId, autopay),
    onSuccess: (status, { clubId }) => {
      qc.setQueryData(queryKeys.billing(clubId), status);
    },
  });
}
