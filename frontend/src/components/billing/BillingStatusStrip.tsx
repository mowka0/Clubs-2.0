import { FC, useState } from 'react';
import { useBillingQuery, useSetAutopayMutation } from '../../queries/billing';
import { useHaptic } from '../../hooks/useHaptic';
import { formatBillingDate, formatRubles } from '../../api/billing';
import { pluralRu } from '../../utils/formatters';

interface BillingStatusStripProps {
  clubId: string;
  /** «Оплатить» / «Продлить» — открыть шит оплаты. */
  onPay: () => void;
  /**
   * Где стоит полоска (billing-member-pays.md M4). `manage` — «Управление»: все состояния,
   * ползунок автопродления владельцу. `club` — главная страница клуба: только когда пора платить
   * (`paymentDue`), зато всем участникам — заплатить за клуб может любой; ползунка нет.
   */
  placement?: 'manage' | 'club';
  /** На главной: показывать «бот удалён» — это тревога для организаторов, а не «пора платить». */
  showBotRemoved?: boolean;
}

/**
 * Полоска статуса биллинга (platform-billing.md § 7, billing-member-pays.md § 6): одна полоска,
 * семь состояний из BillingStatusDto; ползунок автопродления владельца живёт прямо в ней —
 * отдельного экрана «подписка» нет. У клуба без чата полоски нет: ему не за что платить.
 * По тексту платят «за клуб», хотя единица счёта — чат (PO 2026-09-07).
 */
export const BillingStatusStrip: FC<BillingStatusStripProps> = ({ clubId, onPay, placement = 'manage', showBotRemoved = false }) => {
  const haptic = useHaptic();
  const { data } = useBillingQuery(clubId);
  const setAutopay = useSetAutopayMutation();
  const [autopayError, setAutopayError] = useState<string | null>(null);

  if (!data || data.state === 'NO_CHAT') return null;
  const onClubPage = placement === 'club';
  if (onClubPage && !data.paymentDue && !(data.state === 'BOT_REMOVED' && showBotRemoved)) return null;
  // Ползунок — только владельцу и только в «Управлении»: карта для списаний — его (M2).
  const withAutopayToggle = !onClubPage && data.canEnableAutopay;
  // Напоминания в личку получает только владелец (M5) — обещать их остальным нельзя.
  const isOwnerView = data.canEnableAutopay;
  const payerLine = data.lastPayer && (
    <div className="d">Последний платёж — {data.lastPayer.name} 💛</div>
  );

  const price = formatRubles(data.priceKopecks);
  const periodEnd = data.currentPeriodEnd ? formatBillingDate(data.currentPeriodEnd) : null;
  const trialUntil = data.trialUntil ? formatBillingDate(data.trialUntil) : null;
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

  // Ползунок живёт только при сохранённой карте И разрешённом магазину рекурренте: без второго
  // шедулер карту не списывает (шлёт напоминания), и включённый ползунок обещал бы лишнее.
  const autopayLocked = !data.autopayPossible || !data.autopayAvailable;
  const autopayOn = data.autopay && !autopayLocked;

  switch (data.state) {
    case 'BOT_REMOVED':
      return (
        <div className="rd-billing-strip grace" data-state={data.state}>
          <span className="ic" aria-hidden="true">🤖</span>
          <div className="tx">
            <div className="t">Бот удалён из чата — подписка на паузе</div>
            <div className="d">
              Пока бота нет, встречи бесплатны, напоминаний и списаний не будет
              {periodEnd ? `; оплачено до ${periodEnd}, эта дата не сдвигается` : ''}. Верните бота в чат — всё продолжится само.
            </div>
          </div>
        </div>
      );
    case 'TRIAL_NOT_STARTED':
      return (
        <div className="rd-billing-strip free" data-state={data.state}>
          <span className="ic" aria-hidden="true">🎁</span>
          <div className="tx">
            <div className="t">{data.trialDays} {pluralRu(data.trialDays, ['день', 'дня', 'дней'])} бесплатно</div>
            <div className="d">Отсчёт пойдёт с первой встречи. Дальше {price} в месяц за клуб.</div>
          </div>
        </div>
      );
    case 'TRIAL':
      return (
        <div className="rd-billing-strip free" data-state={data.state}>
          <span className="ic" aria-hidden="true">🎁</span>
          <div className="tx">
            <div className="t">Бесплатно до {trialUntil}</div>
            <div className="d">Дальше {price} в месяц за клуб.{isOwnerView && ' Напомним в личке за неделю и за день — можно оплатить заранее.'}</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Оплатить</button>
        </div>
      );
    case 'TRIAL_ENDED':
      return (
        <div className="rd-billing-strip pay" data-state={data.state}>
          <span className="ic" aria-hidden="true">💬</span>
          <div className="tx">
            <div className="t">Бесплатный период закончился</div>
            <div className="d">Новые встречи — по подписке {price} в месяц за клуб. Начатое доживёт, бот из чата не уходит.</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Оплатить</button>
        </div>
      );
    case 'ACTIVE':
      return (
        <div className="rd-billing-strip info" data-state={data.state}>
          <span className="ic" aria-hidden="true">✅</span>
          <div className="tx">
            <div className="t">{onClubPage ? `Клуб оплачен до ${periodEnd}` : `Оплачено до ${periodEnd}`}</div>
            <div className="d">
              {onClubPage
                // На главной полоска появляется за неделю до конца — зовём продлить, не пугая (M4).
                ? (autopayOn
                  ? (data.canEnableAutopay
                    // Владельцу — без «вместо него»: его оплата без отметки согласия выключила бы автопродление.
                    ? `${periodEnd} спишем ${price} с вашей карты.`
                    : `${periodEnd} продлится автоматически с карты владельца.`)
                  : `${price} в месяц за клуб. Продлить можно заранее — месяц прибавится к оплаченному.`)
                : `${price} в месяц за клуб · Robokassa`}
            </div>
            {payerLine}
            {withAutopayToggle && <div className="sub">
              <div className="fi">
                <div className="ft">Продлевать автоматически</div>
                <div className="fd">
                  {!data.autopayAvailable
                    // Рекуррент магазину не разрешён: даже сохранённую карту шедулер не списывает, шлёт напоминания.
                    ? 'Автопродление пока недоступно — напомним в личке за 3 дня и за день до конца периода.'
                    : !data.autopayPossible
                      // Карта не сохранена: оплата по СБП или оплата в период без рекуррента — причина в
                      // тексте не называется, чтобы не обещать «оплатите картой», когда это не поможет.
                      ? 'Карта для автосписания не сохранена — напомним в личке за 3 дня и за день до конца периода.'
                      : data.autopay
                        // Списание — в день окончания оплаченного периода (PO 2026-09-07).
                        ? `${periodEnd} спишем ${price} с сохранённой карты.`
                        : 'Выключено — напомним в личке за 3 дня и за день до конца периода.'}
                </div>
                {autopayError && <div className="rd-billing-err">{autopayError}</div>}
              </div>
              <button
                type="button"
                className={`rd-cl-tgl${autopayOn ? ' on' : ''}`}
                role="switch"
                aria-checked={autopayOn}
                aria-label="Продлевать автоматически"
                disabled={autopayLocked || setAutopay.isPending}
                onClick={toggleAutopay}
              />
            </div>}
          </div>
          {onClubPage && !(autopayOn && data.canEnableAutopay) && <button type="button" className="act" onClick={onPay}>Оплатить</button>}
        </div>
      );
    case 'GRACE':
      return (
        <div className="rd-billing-strip grace" data-state={data.state}>
          <span className="ic" aria-hidden="true">⏳</span>
          <div className="tx">
            <div className="t">Подписка закончилась {periodEnd}</div>
            <div className="d">До <b>{graceUntil}</b> всё работает как раньше. Потом новые встречи — только после оплаты, начатое доживёт.</div>
            {payerLine}
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
            <div className="d">Подписка закончилась {periodEnd}. Начатые встречи доживут, бот из чата не уходит.</div>
          </div>
          <button type="button" className="act" onClick={onPay}>Оплатить</button>
        </div>
      );
    default:
      return null;
  }
};
