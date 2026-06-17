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
function laddered(icon: string, noun: string, tiers: number[], current: number): Milestone[] {
  const out: Milestone[] = [];
  const reached = [...tiers].reverse().find((t) => current >= t);
  if (reached !== undefined) out.push({ icon, label: `${reached} ${noun}`, earned: true });
  const next = tiers.find((t) => current < t);
  if (next !== undefined && current > 0) {
    out.push({ icon, label: `${next} ${noun}`, earned: false, current, target: next });
  }
  return out;
}

export function milestones(facts: ClubFactsDto): Milestone[] {
  return [
    ...laddered('🤝', 'преданных', [5, 10, 25, 50], facts.coreSize),
    ...laddered('🔥', 'встреч', [10, 50, 100, 250, 500], facts.totalMeetings),
    ...(facts.successfulSkladchinas >= 1 ? [{ icon: '💸', label: 'Первый сбор', earned: true }] : []),
  ];
}
