import type { EventFormat } from '../types/api';

/**
 * Словарь бейджей формата встречи — ОДИН на все поверхности: карточка ленты событий, карточка
 * «Активностей», тизер-афиша клуба и шапка страницы встречи. Раньше та же тройка была
 * продублирована в четырёх файлах и разъезжалась (в одном «Обычная», в другом «с местами»).
 *
 * Счётчик «4 / 6» сам по себе не отличает порог от потолка, поэтому бейдж свою строку
 * отрабатывает. Спека: docs/modules/event-formats.md § 7.
 */

/** Эмодзи формата — для мест, где на бейдж нет ширины (строка метаданных афиши). */
export function formatEmoji(format: EventFormat): string {
  switch (format) {
    case 'min': return '🎯';
    case 'max': return '🎟';
    case 'any': return '🌊';
    // Неизвестный литерал (например, `normal` от бэкенда следующей версии, пока у клиента
    // закэширован старый бандл): не ронять страницу, показать нейтральный бейдж.
    default: return '👥';
  }
}

/**
 * Бейдж целиком: «🎯 Не меньше 6». Число берётся из лимита — оно и есть содержание формата,
 * поэтому бейдж без него (легаси-строка без `participantLimit`) вырождается в одно правило.
 * Регистр — забота вызывающего: в шапке встречи и в ленте событий бейдж капсом, в
 * «Активностях» обычный.
 */
export function formatBadge(format: EventFormat, participantLimit: number | null): string {
  const emoji = formatEmoji(format);
  switch (format) {
    case 'min': return participantLimit === null ? `${emoji} Минимум` : `${emoji} Не меньше ${participantLimit}`;
    case 'max': return participantLimit === null ? `${emoji} Максимум` : `${emoji} Не больше ${participantLimit}`;
    case 'any': return `${emoji} Сколько придёт`;
    default: return participantLimit === null ? `${emoji} Встреча` : `${emoji} До ${participantLimit}`;
  }
}
