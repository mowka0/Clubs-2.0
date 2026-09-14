import type { DebtStatus, SkladchinaKind, SkladchinaStatus } from '../types/api';

/** Словарь видов сбора — ровно как в мокапах (skladchina-v3 § 9). */
export const KIND_LABEL: Record<SkladchinaKind, string> = {
  shared: 'Скинуться',
  per_head: 'Кто берёт?',
  voluntary: 'По желанию',
};

/** «Сумму выбираете сами» (§ 3.5): подпись режима вместо «По желанию» у сбора из встречи с суммой. */
export const FREE_AMOUNT_LABEL = 'Сумму выбираете сами';

export const KIND_EMOJI: Record<SkladchinaKind, string> = {
  shared: '💰',
  per_head: '🎫',
  voluntary: '🎁',
};

/**
 * Четыре входа в создание сбора (skladchina-v3 § 13 п. 32): плитка = ключевая особенность,
 * подпись = жизненные ситуации. `split` и `enroll` на бэке один вид `shared`.
 */
export type SkladchinaFlow = 'split' | 'enroll' | 'per_head' | 'voluntary';

export const FLOW_KIND: Record<SkladchinaFlow, SkladchinaKind> = {
  split: 'shared',
  enroll: 'shared',
  per_head: 'per_head',
  voluntary: 'voluntary',
};

export const FLOW_EMOJI: Record<SkladchinaFlow, string> = {
  split: '🧾',
  enroll: '📝',
  per_head: '🎫',
  voluntary: '🎁',
};

export const FLOW_LABEL: Record<SkladchinaFlow, string> = {
  split: 'Кто сколько должен?',
  enroll: 'Кто в деле?',
  per_head: 'Кто берёт?',
  voluntary: 'Кто сколько хочет?',
};

/** Подписи PO 2026-09-13: первыми — ключевые слова про сумму, затем ситуации. */
export const FLOW_SUBTITLE: Record<SkladchinaFlow, string> = {
  split: 'Сумма и люди известны, каждому своя доля до срока: например, счёт в ресторане',
  enroll: 'Сумма общая, доля каждого зависит от того, сколько человек наберётся: общая покупка или аренда',
  per_head: 'Цена за штуку фиксированная, каждый платит только за своё: билеты, мерч, форма',
  voluntary: 'Сумму выбираешь сам, без долгов и репутации: подарок, благодарность и т. д.',
};

export function isSkladchinaFlow(v: string | null): v is SkladchinaFlow {
  return v === 'split' || v === 'enroll' || v === 'per_head' || v === 'voluntary';
}

export function statusLabel(status: SkladchinaStatus): string {
  switch (status) {
    case 'active': return 'Идёт';
    case 'collected': return 'Собран';
    case 'cancelled': return 'Отменён';
  }
}

/** Состояние долга словами — из таблицы переходов § 2.2, без «свести»/«засчитать»/«не дошёл». */
export function debtStatusLabel(status: DebtStatus): string {
  switch (status) {
    case 'waiting': return 'ждём';
    case 'promised': return 'обещал';
    case 'claimed': return 'говорит, что отдал';
    case 'received': return 'получено';
    case 'forgiven': return 'прощён';
    case 'dropped': return 'выбыл';
  }
}

export const DATE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit',
});

export const DAY_FMT = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });

/** «Иван Петров» / «Иван». */
export function personName(p: { firstName: string; lastName: string | null }): string {
  return p.lastName ? `${p.firstName} ${p.lastName}` : p.firstName;
}

export function initials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

/** Дата обещания по умолчанию — через три дня; поле `type="date"` ждёт `YYYY-MM-DD`. */
export function defaultPromiseDate(): string {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  return d.toISOString().slice(0, 10);
}
