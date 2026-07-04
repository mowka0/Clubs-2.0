export type ReliabilityTier = 'high' | 'mid' | 'low' | 'new';

/**
 * Отображает P1b Trust-скор 0-100 в отображаемый тир. `null` = «Новичок» (ещё нет истории).
 * Пороги зеркалят бэкенд: ≥70 — граница «надёжный» (TrustPolicy.RELIABLE_THRESHOLD),
 * ≥85 — сильный тир. Единый источник для всех поверхностей репутации, чтобы раскраска была консистентной.
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

/** Русская подпись тира для глобального заголовка («высокая/средняя/низкая надёжность»). */
export function tierWord(score: number | null): string {
  return TIER_WORD[reliabilityTier(score)];
}

/** «клубе» (1) / «клубах» (2+) — предложный падеж множественного числа для «опыт в N …». */
export function clubsPrepositional(n: number): string {
  return n === 1 ? 'клубе' : 'клубах';
}
