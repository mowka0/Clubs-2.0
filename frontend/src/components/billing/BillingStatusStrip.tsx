import { FC, useState } from 'react';
import { useBillingQuery, useSetAutopayMutation } from '../../queries/billing';
import { useHaptic } from '../../hooks/useHaptic';
import { formatBillingDate, formatRubles } from '../../api/billing';

interface BillingStatusStripProps {
  clubId: string;
  /** «Оплатить» / «Продлить» — открыть шит оплаты. */
  onPay: () => void;
}

/**
 * Полоска статуса биллинга на странице управления клубом (platform-billing.md § 7): одна
 * полоска, шесть состояний из BillingStatusDto; ползунок автопродления живёт прямо в ней —
 * отдельного экрана «подписка» нет. У клуба без чата полоски нет: ему не за что платить.
 * По тексту платят «за клуб», хотя единица счёта — чат (PO 2026-09-07).
 */
export const BillingStatusStrip: FC<BillingStatusStripProps> = ({ clubId, onPay }) => {
  const haptic = useHaptic();
  const { data } = useBillingQuery(clubId);
  const setAutopay = useSetAutopayMutation();
  const [autopayError, setAutopayError] = useState<string | null>(null);

  if (!data || data.state === 'NO_CHAT') return null;

  const price = formatRubles(data.priceKopecks);
  const periodEnd = data.currentPeriodEnd ? formatBillingDate(data.currentPeriodEnd) : null;
  const graceUntil = data.graceUntil ? formatBillingDate(data.graceUntil) : null;

  const toggleAutopay = () => {
    if (setAutopay.isPending) return;
    haptic.select();
    setAutopayError(null);
    setAutopay.mutate(
      { clubId, autopay: !data.autopay },
      { onError: (e) => { haptic.notify('error'); setAutopayError(e instanceof Error ? e.message : 'Не удалось изменить'); } },
    );
  };

  switch (data.state) {
    case 'FREE_MEETING_AVAILABLE':
      return (
        <div className="rd-billing-strip free" data-state={data.state}>
          <span className="ic" aria-hidden="true">🎁</span>
          <div className="tx">
            <div className="t">Первая встреча — бесплатно</div>
            <div className="d">Дальше {price} в месяц за клуб. Счёт появится при создании второй встречи.</div>
          </div>
        </div>
      );
    case 'FREE_MEETING_USED':
      return (
        <div className="rd-billing-strip pay" data-state={data.state}>
          <span className="ic" aria-hidden="true">💬</span>
          <div className="tx">
            <div className="t">Бесплатная встреча использована</div>
            <div className="d">Следующая — по подписке {price} в месяц за клуб. Отмените первую до старта — бесплатная вернётся.</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Оплатить</button>
        </div>
      );
    case 'ACTIVE':
      return (
        <div className="rd-billing-strip info" data-state={data.state}>
          <span className="ic" aria-hidden="true">✅</span>
          <div className="tx">
            <div className="t">Оплачено до {periodEnd}</div>
            <div className="d">{price} в месяц за клуб · Robokassa</div>
            <div className="sub">
              <div className="fi">
                <div className="ft">Продлевать автоматически</div>
                <div className="fd">
                  {!data.autopayPossible
                    ? 'Недоступно для СБП — напомним в личке за 3 дня и за день. Оплатите картой в следующий раз, и ползунок включится.'
                    : data.autopay
                      // Списание — в день окончания оплаченного периода (PO 2026-09-07).
                      ? `${periodEnd} спишем ${price} с сохранённой карты.`
                      : 'Выключено — напомним в личке за 3 дня и за день до конца периода.'}
                </div>
                {autopayError && <div className="rd-billing-err">{autopayError}</div>}
              </div>
              <button
                type="button"
                className={`rd-cl-tgl${data.autopay && data.autopayPossible ? ' on' : ''}`}
                role="switch"
                aria-checked={data.autopay && data.autopayPossible}
                aria-label="Продлевать автоматически"
                disabled={!data.autopayPossible || setAutopay.isPending}
                onClick={toggleAutopay}
              />
            </div>
          </div>
        </div>
      );
    case 'GRACE':
      return (
        <div className="rd-billing-strip grace" data-state={data.state}>
          <span className="ic" aria-hidden="true">⏳</span>
          <div className="tx">
            <div className="t">Подписка закончилась {periodEnd}</div>
            <div className="d">До <b>{graceUntil}</b> всё работает как раньше. Потом новые встречи — только после оплаты, начатое доживёт.</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Продлить</button>
        </div>
      );
    case 'ENDED':
      return (
        <div className="rd-billing-strip ended" data-state={data.state}>
          <span className="ic" aria-hidden="true">🚫</span>
          <div className="tx">
            <div className="t">Новые встречи недоступны до оплаты</div>
            <div className="d">Подписка закончилась {periodEnd}{graceUntil ? `, грейс вышел ${graceUntil}` : ''}. Начатые встречи доживут, чат бот не бросает.</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Оплатить</button>
        </div>
      );
    default:
      return null;
  }
};
