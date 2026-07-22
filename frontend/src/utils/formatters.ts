export function formatPrice(price: number): string {
  return price === 0 ? 'Бесплатно' : `${price} ₽ / мес`;
}

/**
 * Выбор русской формы множественного числа: `forms = [one, few, many]`, например
 * ['месяц','месяца','месяцев']. (Такая же логика инлайнится в паре старых компонентов —
 * позже стоит консолидировать их сюда.)
 */
export function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

/** Один и тот же локальный календарный день (Y/M/D)? */
function isSameLocalDay(d: Date, ref: Date): boolean {
  return (
    d.getFullYear() === ref.getFullYear() &&
    d.getMonth() === ref.getMonth() &&
    d.getDate() === ref.getDate()
  );
}

/**
 * Совпадает ли ISO-дата с «сегодня» по локальному календарю (Y/M/D), а не окну
 * «ближайшие 24 часа». Тесты детерминируют «сейчас» через fake timers.
 */
export function isToday(iso: string): boolean {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return false;
  return isSameLocalDay(d, new Date());
}

/** «Завтра» по локальному календарю; setDate корректно перекатывает месяц/год. */
export function isTomorrow(iso: string): boolean {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return false;
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  return isSameLocalDay(d, tomorrow);
}

/** Время «HH:MM» для бейджей встречи (обложка карточки, полка «сегодня»). */
export function formatTimeHM(iso: string): string {
  const date = new Date(iso);
  const hh = date.getHours().toString().padStart(2, '0');
  const mm = date.getMinutes().toString().padStart(2, '0');
  return `${hh}:${mm}`;
}

export function formatDatetime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('ru-RU', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
