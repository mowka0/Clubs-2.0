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
