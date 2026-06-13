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

const TIER_WORD: Record<ReliabilityTier, string> = {
  high: 'высокая надёжность',
  mid: 'средняя надёжность',
  low: 'низкая надёжность',
  new: '',
};

/** Russian tier label for the global headline ("высокая/средняя/низкая надёжность"). */
export function tierWord(score: number | null): string {
  return TIER_WORD[reliabilityTier(score)];
}

/** "клубе" (1) / "клубах" (2+) — prepositional plural for "опыт в N …". */
export function clubsPrepositional(n: number): string {
  return n === 1 ? 'клубе' : 'клубах';
}
