import type { EventFormat } from '../types/api';

/**
 * Словарь бейджей формата встречи — ОДИН на все поверхности: карточка ленты событий, карточка
 * «Активностей», тизер-афиша клуба, шапка страницы встречи и строка шаблона в «+». Раньше та же
 * тройка была продублирована в четырёх файлах и разъезжалась.
 *
 * Счётчик «4 / 10» сам по себе не говорит, есть ли порог, поэтому его несёт бейдж: «👥 4–10»
 * при включённом минимуме, «👥 До 10» без него. Спека: docs/modules/event-formats.md § 9.1.
 */

/** Эмодзи формата — для мест, где на бейдж нет ширины (строка метаданных афиши). */
export function formatEmoji(format: EventFormat): string {
  switch (format) {
    case 'normal': return '👥';
    case 'open': return '🌊';
    // Неизвестный литерал (бэкенд следующей версии при закэшированном старом бандле — известный
    // хвост immutable-кэша): не ронять страницу, показать нейтральный бейдж.
    default: return '👥';
  }
}

/**
 * «4–10» (en-dash) при включённом минимуме, «До 10» без него; минимум, равный максимуму, — это
 * «ровно N» (четыре места в машине), а не «6–6», которое читается как опечатка.
 */
function limitRange(participantLimit: number, minParticipants: number | null): string {
  if (minParticipants === null) return `До ${participantLimit}`;
  if (minParticipants === participantLimit) return `Ровно ${participantLimit}`;
  return `${minParticipants}–${participantLimit}`;
}

/**
 * Бейдж целиком: «👥 4–10» / «👥 До 10» / «🌊 Открытая». Регистр — забота вызывающего: в шапке
 * встречи и в ленте событий бейдж капсом, в «Активностях» обычный.
 */
export function formatBadge(
  format: EventFormat,
  participantLimit: number | null,
  minParticipants: number | null,
): string {
  const emoji = formatEmoji(format);
  switch (format) {
    case 'open': return `${emoji} Открытая`;
    case 'normal': return participantLimit === null
      ? `${emoji} Встреча`
      : `${emoji} ${limitRange(participantLimit, minParticipants)}`;
    // Ветка default ОБЯЗАТЕЛЬНА (§ 8 «Совместимость»): страница встречи делает .toUpperCase()
    // над результатом, и undefined от switch без default ронял бы её белым экраном.
    default: return participantLimit === null ? `${emoji} Встреча` : `${emoji} До ${participantLimit}`;
  }
}

/**
 * Формат словами, без эмодзи — для приглушённой строки метаданных шаблона в «+»: цветной эмодзи
 * там рисовался платформенным шрифтом и выбивался из строки (правка PO 2026-08-11).
 */
export function formatWords(
  format: EventFormat,
  participantLimit: number | null,
  minParticipants: number | null,
): string {
  if (format === 'open') return 'открытая';
  if (participantLimit === null) return 'встреча';
  if (minParticipants === null) return `до ${participantLimit} человек`;
  if (minParticipants === participantLimit) return `ровно ${participantLimit} человек`;
  return `${minParticipants}–${participantLimit} человек`;
}
