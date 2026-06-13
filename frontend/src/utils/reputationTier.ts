export type ReliabilityTier = 'high' | 'mid' | 'low' | 'new';

/**
 * Maps a P1b Trust 0-100 score to a display tier. `null` = "Новичок" (no track record yet).
 * Thresholds mirror the backend: ≥70 is the "reliable" cutoff (TrustPolicy.RELIABLE_THRESHOLD),
 * ≥85 a strong tier. Single source for every reputation surface so the colouring stays consistent.
 */
export function reliabilityTier(score: number | null): ReliabilityTier {
  if (score === null) return 'new';
  if (score >= 85) return 'high';
  if (score >= 70) return 'mid';
  return 'low';
}
