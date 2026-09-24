import { pluralRu } from '../../utils/formatters';
import { OFFER_UPDATED, offerSections, type OfferSection } from './offerSections.generated';

/**
 * Публичная оферта — показывается разделами внутри шита оплаты и на `/about`. Канонический
 * текст — `docs/legal/oferta.md` (шаблон Robokassa «Оказание услуг» + наши разделы 3 и 11);
 * `offerSections.generated.ts` генерирует `scripts/gen-oferta.py`, руками его не правят, CI
 * сверяет синхронность. Та же генерация даёт копию боту (`backend/.../bot/OfferSections.kt`).
 */
export const OFFER_TITLE = 'Условия (публичная оферта)';

export { OFFER_UPDATED, type OfferSection };

export interface OfferInput {
  recipientName: string;
  inn: string;
  priceLabel: string;
  /** Число дней бесплатного периода — из настроек сервера или константы бандла. */
  trialDays: number;
  /** Без @ — как в SUPPORT.telegram. */
  supportTelegram: string;
  supportEmail: string;
}

/** «1 день», «2 дня», «15 дней». */
export function trialDaysLabel(days: number): string {
  return `${days} ${pluralRu(days, ['день', 'дня', 'дней'])}`;
}

export function offer(input: OfferInput): OfferSection[] {
  return offerSections({
    recipientName: input.recipientName || 'исполнитель',
    inn: input.inn,
    priceLabel: input.priceLabel,
    trialDaysLabel: trialDaysLabel(input.trialDays),
    supportTelegram: `@${input.supportTelegram}`,
    supportEmail: input.supportEmail,
  });
}
