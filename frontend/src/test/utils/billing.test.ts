import { describe, it, expect } from 'vitest';
import { ApiError } from '../../api/apiClient';
import { formatRubles, paywallFromError } from '../../api/billing';

describe('billing helpers', () => {
  it('paywallFromError читает payload 402 и игнорирует остальные ошибки', () => {
    const paywall = paywallFromError(
      new ApiError(402, 'Subscription required', {
        error: 'PAYMENT_REQUIRED', message: 'Subscription required',
        reason: 'SUBSCRIPTION_EXPIRED', clubId: 'club-1', priceKopecks: 19900,
      }),
    );
    expect(paywall).toEqual({ reason: 'SUBSCRIPTION_EXPIRED', clubId: 'club-1', priceKopecks: 19900, message: 'Subscription required' });

    expect(paywallFromError(new ApiError(403, 'Forbidden', { reason: 'TRIAL_ENDED', clubId: 'x' }))).toBeNull();
    expect(paywallFromError(new ApiError(402, 'no body'))).toBeNull();
    expect(paywallFromError(new Error('network'))).toBeNull();
  });

  it('formatRubles показывает копейки только когда они есть', () => {
    expect(formatRubles(19900)).toBe('199 ₽');
    expect(formatRubles(19950)).toBe('199,50 ₽');
    expect(formatRubles(5)).toBe('0,05 ₽');
  });
});
