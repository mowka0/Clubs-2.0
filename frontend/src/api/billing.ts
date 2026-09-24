import { apiClient, ApiError } from './apiClient';

/** Зеркалит backend BillingState (platform-billing.md § 6.6). */
export type BillingState =
  | 'NO_CHAT'
  | 'BOT_REMOVED'
  | 'TRIAL_NOT_STARTED'
  | 'TRIAL'
  | 'TRIAL_ENDED'
  | 'ACTIVE'
  | 'GRACE'
  | 'ENDED';

/** Причина стены из 402 (backend PaywallReason). */
export type PaywallReason = 'TRIAL_ENDED' | 'SUBSCRIPTION_EXPIRED';

export interface BillingStatusDto {
  state: BillingState;
  priceKopecks: number;
  /** До какого момента чат живёт бесплатно; null — период не начат или уже неважен. */
  trialUntil: string | null;
  /** Длина бесплатного периода в днях — приходит с сервера, в текстах не зашита. */
  trialDays: number;
  currentPeriodEnd: string | null;
  graceUntil: string | null;
  autopay: boolean;
  autopayPossible: boolean;
  /** Есть свежий неоплаченный счёт — «проверяем оплату». */
  pendingCheckout: boolean;
  /** ФИО самозанятого-получателя целиком; пусто = не настроено на сервере. */
  recipientName: string;
  /** Смотрящий может платить: платит только владелец клуба, со-организатор видит статус, но не кнопку. */
  canPay: boolean;
}

export interface CheckoutDto {
  paymentUrl: string;
  invId: number;
}

/** Payload 402 (backend PaywallResponse): по нему форма встречи открывает шит оплаты. */
export interface PaywallInfo {
  reason: PaywallReason;
  clubId: string;
  priceKopecks: number;
  message: string;
}

/**
 * Строка обещания в точках входа без клуба (экран «Выбрать чат», мастер): там ещё нет клуба,
 * у которого можно спросить цену. Меняется вместе с subscription_pricing на бэкенде.
 * По тексту платят «за клуб» (PO 2026-09-07), хотя единица счёта — чат.
 */
export const TRIAL_DAYS_DEFAULT = 15;
export const CHAT_PRICE_LABEL = '199 ₽';
export const CHAT_PRICE_LINE = `Первые ${TRIAL_DAYS_DEFAULT} дней бесплатно. Дальше ${CHAT_PRICE_LABEL} в месяц за клуб.`;

/**
 * Реквизиты продавца и контакты для публичных текстов (оферта, `/about`, `/privacy`). Живут в бандле:
 * публичные страницы работают без API. ФИО должно совпадать с BILLING_RECIPIENT_NAME бэкенда — его же
 * человек видит в шите оплаты. Значения даны PO 2026-09-16; e-mail можно сменить в любой момент.
 * ИНН в DTO биллинга пока нет — константа здесь единственный источник для шита (backlog).
 */
export const SELLER = {
  /** ФИО самозанятого целиком — как в «Мой налог». */
  name: 'Варламов Иван Михайлович',
  /** ИНН самозанятого — обязателен на странице продавца и в оферте. */
  inn: '370211562724',
} as const;

export const SUPPORT = {
  /** Аккаунт поддержки без @ — тот же, куда бот шлёт «Сообщить о проблеме». */
  telegram: 'clubs_tech_support',
  /** E-mail для обращений; пусто — строка не показывается. */
  email: 'clubs.techsupport@gmail.com',
} as const;

export function getBilling(clubId: string): Promise<BillingStatusDto> {
  return apiClient.get<BillingStatusDto>(`/api/clubs/${clubId}/billing`);
}

export function startCheckout(clubId: string, autopay: boolean): Promise<CheckoutDto> {
  return apiClient.post<CheckoutDto>(`/api/clubs/${clubId}/billing/checkout`, { autopay });
}

export function setAutopay(clubId: string, autopay: boolean): Promise<BillingStatusDto> {
  return apiClient.patch<BillingStatusDto>(`/api/clubs/${clubId}/billing/autopay`, { autopay });
}

/** Извлекает payload пейволла из ApiError 402, либо null, если ошибка не про оплату. */
export function paywallFromError(error: unknown): PaywallInfo | null {
  if (!(error instanceof ApiError) || error.status !== 402) return null;
  const body = error.body;
  if (body && typeof body === 'object' && 'reason' in body && 'clubId' in body) {
    const b = body as Record<string, unknown>;
    const reason = b.reason === 'SUBSCRIPTION_EXPIRED' ? 'SUBSCRIPTION_EXPIRED' : 'TRIAL_ENDED';
    return {
      reason,
      clubId: String(b.clubId),
      priceKopecks: Number(b.priceKopecks ?? 0),
      message: String(b.message ?? ''),
    };
  }
  return null;
}

/** «199 ₽» из копеек; копейки показываем только когда они есть. */
export function formatRubles(kopecks: number): string {
  const rub = Math.floor(kopecks / 100);
  const kop = kopecks % 100;
  return kop === 0 ? `${rub} ₽` : `${rub},${String(kop).padStart(2, '0')} ₽`;
}

/**
 * «первые 15 дней были бесплатными» / «первый день был бесплатным» — подпись под ценой в шите.
 * Единица отдельной веткой: «первые 1 день» не читается ни при каком склонении.
 */
export function trialPassedLabel(days: number, pluralize: (n: number) => string): string {
  return days === 1 ? 'первый день был бесплатным' : `первые ${days} ${pluralize(days)} были бесплатными`;
}

/** «7 октября» — даты биллинга в DM и на экранах одним форматом. */
export function formatBillingDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}
