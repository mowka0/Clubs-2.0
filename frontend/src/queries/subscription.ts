import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelSubscription,
  getSubscriptionPlans,
  getSubscriptionStatus,
  subscribeToPlan,
} from '../api/subscription';
import { queryKeys } from './queryKeys';

export function useSubscriptionStatusQuery() {
  return useQuery({
    queryKey: queryKeys.subscription.status,
    queryFn: getSubscriptionStatus,
  });
}

export function useSubscriptionPlansQuery(enabled = true) {
  return useQuery({
    queryKey: queryKeys.subscription.plans,
    queryFn: getSubscriptionPlans,
    enabled,
    staleTime: 5 * 60 * 1000, // pricing rarely changes within a session
  });
}

export function useSubscribeMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (plan: string) => subscribeToPlan(plan),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.subscription.status });
    },
  });
}

export function useCancelSubscriptionMutation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => cancelSubscription(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.subscription.status });
    },
  });
}
