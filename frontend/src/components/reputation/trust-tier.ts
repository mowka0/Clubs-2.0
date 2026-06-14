export type TrustTier = 'high' | 'mid' | 'low';

/**
 * Trust 0-100 → tier for the Надёжность ring color. The green band starts at 70 to align with
 * the backend's RELIABLE_THRESHOLD (the score at which a club counts as "reliable"); accent is
 * the middle band; danger flags clearly-low trust. Purely presentational.
 */
export function trustTier(trust: number): TrustTier {
  if (trust >= 70) return 'high';
  if (trust >= 45) return 'mid';
  return 'low';
}

export const TRUST_TIER_COLOR: Record<TrustTier, string> = {
  high: 'var(--live)',
  mid: 'var(--accent)',
  low: 'var(--danger)',
};
