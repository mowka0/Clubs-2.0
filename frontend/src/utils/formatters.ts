export function formatPrice(price: number): string {
  return price === 0 ? 'Бесплатно' : `${price} Stars / мес`;
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
