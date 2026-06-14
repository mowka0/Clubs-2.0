import { describe, it, expect } from 'vitest';
import { trustTier, TRUST_TIER_COLOR } from '../../components/reputation/trust-tier';

describe('trustTier', () => {
  it('maps trust to tier at the band boundaries', () => {
    expect(trustTier(100)).toBe('high');
    expect(trustTier(70)).toBe('high');
    expect(trustTier(69)).toBe('mid');
    expect(trustTier(45)).toBe('mid');
    expect(trustTier(44)).toBe('low');
    expect(trustTier(0)).toBe('low');
  });

  it('exposes a distinct color per tier', () => {
    expect(TRUST_TIER_COLOR.high).toContain('--live');
    expect(TRUST_TIER_COLOR.mid).toContain('--accent');
    expect(TRUST_TIER_COLOR.low).toContain('--danger');
  });
});
