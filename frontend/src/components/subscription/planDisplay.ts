/** Display helpers for subscription plans (Вариант A · тарифные карточки). */

export const PLAN_ORDER: readonly string[] = ['FREE', 'TRIO', 'UNLIMITED'];

export const PLAN_LABEL: Record<string, string> = {
  FREE: 'Бесплатный',
  TRIO: 'До 3 клубов',
  UNLIMITED: 'Безлимит',
};

export function planLabel(plan: string): string {
  return PLAN_LABEL[plan] ?? plan;
}

export function planRank(plan: string): number {
  const i = PLAN_ORDER.indexOf(plan);
  return i === -1 ? 0 : i;
}

export function formatRub(kopecks: number): string {
  return `${Math.round(kopecks / 100)} ₽`;
}

export function planCapacityLabel(maxPaidClubs: number | null): string {
  if (maxPaidClubs == null) return 'Сколько угодно клубов';
  return maxPaidClubs === 1 ? '1 платный клуб' : `До ${maxPaidClubs} платных клубов`;
}

export function formatPeriodEnd(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString('ru-RU');
}
