import { pluralRu } from '../../utils/formatters';

/** Lifetime counters need only these two fields — works for both full facts and card facts. */
type CounterFacts = { totalMeetings: number; successfulSkladchinas: number };

/**
 * Достижения клуба — простые ЖИВЫЕ счётчики (без очков, без порогов/замков): возраст-бейдж +
 * накопительные итоги «N встреч» / «N сборов». Показываем фактические числа, что клуб реально набрал,
 * а не выдуманные ступени. Решение PO (2026-06-17): счётчик честнее и понятнее лестницы с замками.
 * «Преданные» здесь не дублируем — это кольцо «основа клуба».
 */

export interface Achievement {
  icon: string;
  label: string;
}

export function ageBadge(ageMonths: number): Achievement {
  if (ageMonths >= 24) {
    const years = Math.floor(ageMonths / 12);
    return { icon: '🎂', label: `Клубу ${years} ${pluralRu(years, ['год', 'года', 'лет'])}` };
  }
  if (ageMonths >= 12) return { icon: '🎂', label: 'Год клубу' };
  if (ageMonths >= 1) return { icon: '🎂', label: `Клубу ${ageMonths} мес` };
  return { icon: '🎂', label: 'Клубу меньше месяца' };
}

/** Lifetime activity counters — each shown only when > 0 (a young club shows just the age badge). */
export function counters(facts: CounterFacts): Achievement[] {
  const out: Achievement[] = [];
  if (facts.totalMeetings > 0) {
    out.push({
      icon: '🔥',
      label: `${facts.totalMeetings} ${pluralRu(facts.totalMeetings, ['встреча', 'встречи', 'встреч'])}`,
    });
  }
  if (facts.successfulSkladchinas > 0) {
    out.push({
      icon: '💸',
      label: `${facts.successfulSkladchinas} ${pluralRu(facts.successfulSkladchinas, ['сбор', 'сбора', 'сборов'])}`,
    });
  }
  return out;
}
