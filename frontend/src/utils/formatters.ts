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

/**
 * Разница в целых локальных календарных днях между ISO-датой и «сегодня»:
 * 0 = сегодня, 1 = завтра, отрицательное = в прошлом. null — невалидная дата.
 * Считается от локальных полуночей (Math.round гасит возможный сдвиг DST).
 */
export function calendarDayDiff(iso: string): number | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const now = new Date();
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const MS_PER_DAY = 24 * 60 * 60 * 1000;
  return Math.round((startOfDay(d) - startOfDay(now)) / MS_PER_DAY);
}

/**
 * Совпадает ли ISO-дата с «сегодня» по локальному календарю (Y/M/D), а не окну
 * «ближайшие 24 часа». Тесты детерминируют «сейчас» через fake timers.
 */
export function isToday(iso: string): boolean {
  return calendarDayDiff(iso) === 0;
}

/** «Завтра» по локальному календарю (перекат месяца/года учтён в calendarDayDiff). */
export function isTomorrow(iso: string): boolean {
  return calendarDayDiff(iso) === 1;
}

/** Слово дня для бейджей недели: «сегодня» / «завтра» / короткий день недели («пт»). */
export function formatNearDay(iso: string): string {
  const diff = calendarDayDiff(iso);
  if (diff === 0) return 'сегодня';
  if (diff === 1) return 'завтра';
  return new Date(iso).toLocaleDateString('ru-RU', { weekday: 'short' });
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
