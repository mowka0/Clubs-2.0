/**
 * Зеркало серверного InterestNormalizer — чипы показывают ту каноничную форму, которая
 * реально сохранится. Общее для интересов профиля и тем клуба: словарь один (club-interests.md).
 */

/** Максимальная длина одного интереса (символов) — ширина колонки interests.name. */
export const INTEREST_MAX_LEN = 40;
/** Максимум интересов в профиле. */
export const MAX_PROFILE_INTERESTS = 15;
/** Максимум тем у клуба. Меньше профильного намеренно: темы — витрина для чужого поиска. */
export const MAX_CLUB_INTERESTS = 7;

// Обрамляющие кавычки — срезаются с краёв строки при нормализации.
const QUOTES = /^["'«»“”‘’`]+|["'«»“”‘’`]+$/g;

export function normalizeInterest(raw: string): string {
  return raw
    .normalize('NFC')
    .trim()
    .replace(QUOTES, '')
    .trim()
    .replace(/\s+/g, ' ')
    .toLowerCase()
    .replace(/ё/g, 'е')
    .slice(0, INTEREST_MAX_LEN);
}

/** Чистое слияние: нормализует сырые токены и добавляет новые к текущему списку (лимит, дедуп). */
export function mergeInterests(current: string[], raw: string[], limit: number): string[] {
  const next = [...current];
  for (const part of raw) {
    const normalized = normalizeInterest(part);
    if (!normalized) continue;
    if (next.length >= limit) break;
    if (!next.includes(normalized)) next.push(normalized);
  }
  return next;
}
