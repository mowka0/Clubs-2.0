import { pluralRu } from '../../utils/formatters';
import type { ClubFactsDto } from '../../types/api';

/**
 * Достижения клуба — L1-fact-backed майлстоны, БЕЗ очков (дизайн §11.2/§7). Каждый — либо взят
 * (earned), либо ближайшая цель с прогрессом. Лестничные пороги: показываем высшую взятую ступень
 * + следующую цель, чтобы и зрелый, и растущий клуб видели смысл. Возраст — отдельный бейдж (всегда).
 */

export interface Milestone {
  icon: string;
  label: string;
  earned: boolean;
  /** progress towards an unearned milestone (omitted when earned) */
  current?: number;
  target?: number;
}

export function ageBadge(ageMonths: number): { icon: string; label: string } {
  if (ageMonths >= 24) {
    const years = Math.floor(ageMonths / 12);
    return { icon: '🎂', label: `Клубу ${years} ${pluralRu(years, ['год', 'года', 'лет'])}` };
  }
  if (ageMonths >= 12) return { icon: '🎂', label: 'Год клубу' };
  if (ageMonths >= 1) return { icon: '🎂', label: `Клубу ${ageMonths} мес` };
  return { icon: '🎂', label: 'Клубу меньше месяца' };
}

/** Highest reached tier (earned) + the next tier (goal, only if already started). */
function laddered(icon: string, tiers: number[], current: number, label: (n: number) => string): Milestone[] {
  const out: Milestone[] = [];
  const reached = [...tiers].reverse().find((t) => current >= t);
  if (reached !== undefined) out.push({ icon, label: label(reached), earned: true });
  const next = tiers.find((t) => current < t);
  if (next !== undefined && current > 0) {
    out.push({ icon, label: label(next), earned: false, current, target: next });
  }
  return out;
}

/** «Первый сбор» for the first, then «N сборов» with plural. */
function skladchinaLabel(n: number): string {
  return n === 1 ? 'Первый сбор' : `${n} ${pluralRu(n, ['сбор', 'сбора', 'сборов'])}`;
}

export function milestones(facts: ClubFactsDto): Milestone[] {
  return [
    ...laddered('🤝', [5, 10, 25, 50], facts.coreSize, (n) => `${n} преданных`),
    ...laddered('🔥', [10, 50, 100, 250, 500], facts.totalMeetings, (n) => `${n} встреч`),
    ...laddered('💸', [1, 5, 10, 25], facts.successfulSkladchinas, skladchinaLabel),
  ];
}
