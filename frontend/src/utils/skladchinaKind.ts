import type { DebtStatus, SkladchinaKind, SkladchinaStatus } from '../types/api';

/** Словарь видов сбора — ровно как в мокапах (skladchina-v3 § 9). */
export const KIND_LABEL: Record<SkladchinaKind, string> = {
  shared: 'Скинуться',
  per_head: 'Кто берёт?',
  voluntary: 'По желанию',
};

export const KIND_EMOJI: Record<SkladchinaKind, string> = {
  shared: '💰',
  per_head: '🎫',
  voluntary: '🎁',
};

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
