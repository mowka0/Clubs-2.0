import { pluralRu } from '../../utils/formatters';
import type { ClubFactsDto } from '../../types/api';

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

/** Счётчики активности за всё время — каждый показан только когда > 0 (молодой клуб покажет только бейдж возраста). */
export function counters(facts: ClubFactsDto): Achievement[] {
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
