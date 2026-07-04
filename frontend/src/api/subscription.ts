import { apiClient, ApiError } from './apiClient';

/** Зеркалит backend SubscriptionStatusDto. `status` равен null на неявном плане FREE; maxPaidClubs null = без ограничений. */
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

/** Payload с кодом 402, который возвращает бэкенд, когда платный клуб превышает потолок плана. */
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

/** Извлекает payload paywall из ApiError с кодом 402, либо null, если ошибка не про paywall. */
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
