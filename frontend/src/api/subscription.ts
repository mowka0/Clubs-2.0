import { apiClient, ApiError } from './apiClient';

/** Mirrors backend SubscriptionStatusDto. `status` is null on the implicit FREE plan; maxPaidClubs null = unlimited. */
export interface SubscriptionStatusDto {
  plan: string;
  status: string | null;
  currentPeriodEnd: string | null;
  maxPaidClubs: number | null;
  priceKopecks: number;
}

export interface PlanOptionDto {
  plan: string;
  maxPaidClubs: number | null;
  priceKopecks: number;
}

/** The 402 payload the backend returns when a paid club exceeds the plan ceiling. */
export interface PaywallInfo {
  currentPlan: string;
  requiredPlan: string;
  priceKopecks: number;
  message: string;
}

export function getSubscriptionStatus(): Promise<SubscriptionStatusDto> {
  return apiClient.get<SubscriptionStatusDto>('/api/subscriptions/status');
}

export function getSubscriptionPlans(): Promise<PlanOptionDto[]> {
  return apiClient.get<PlanOptionDto[]>('/api/subscriptions/plans');
}

export function subscribeToPlan(plan: string): Promise<SubscriptionStatusDto> {
  return apiClient.post<SubscriptionStatusDto>('/api/subscriptions', { plan });
}

export function cancelSubscription(): Promise<SubscriptionStatusDto> {
  return apiClient.post<SubscriptionStatusDto>('/api/subscriptions/cancel');
}

/** Extracts the paywall payload from a 402 ApiError, or null if the error isn't a paywall. */
export function paywallFromError(error: unknown): PaywallInfo | null {
  if (!(error instanceof ApiError) || error.status !== 402) return null;
  const body = error.body;
  if (body && typeof body === 'object' && 'requiredPlan' in body) {
    const b = body as Record<string, unknown>;
    return {
      currentPlan: String(b.currentPlan ?? ''),
      requiredPlan: String(b.requiredPlan ?? ''),
      priceKopecks: Number(b.priceKopecks ?? 0),
      message: String(b.message ?? ''),
    };
  }
  return null;
}
