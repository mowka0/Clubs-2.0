import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useBillingQuery, useStartCheckoutMutation } from '../../queries/billing';
import { useClubQuery } from '../../queries/clubs';
import { useHaptic } from '../../hooks/useHaptic';
import { useSheetDrag } from '../../hooks/useSheetDrag';
import { openExternalLink } from '../../utils/telegramLinks';
import { formatBillingDate, formatRubles, type PaywallReason } from '../../api/billing';
import { OFFER_TITLE, offerParagraphs } from './offerText';

/** Опрос статуса после ухода на оплату: ResultURL провайдера может отставать от возврата. */
const POLL_INTERVAL_MS = 3000;
/** Дольше не ждём — честно говорим, что подтверждение придёт в личку. */
const POLL_TIMEOUT_MS = 60_000;

type Mode = 'pay' | 'waiting' | 'paid' | 'timeout';

interface BillingSheetProps {
  clubId: string;
  /** Причина стены из 402; null — шит открыт из полоски статуса или по ссылке из DM. */
  reason: PaywallReason | null;
  /** «Ждём подтверждения» сразу — возврат из браузера (`?billing=done`, `startapp=billing_…`). */
  initialMode?: 'pay' | 'waiting';
  onClose: () => void;
  /** Оплата подтверждена — вызывающий закрывает шит и продолжает начатое (форма встречи). */
  onPaid?: () => void;
}

/**
 * Шит оплаты за клуб (platform-billing.md § 7): сумма, ползунок автопродления, как проходит
 * оплата, оферта текстом, кнопка → страница провайдера во внешнем браузере → «проверяем
 * оплату…» → «оплачено до». Возврат в приложение оплату НЕ подтверждает — только статус с
 * бэкенда (ResultURL). По тексту платят «за клуб», хотя единица счёта — чат (PO 2026-09-07).
 * Донор вёрстки — DuesPaymentSheet (портал, шапка, сумма, кнопка).
 */
export const BillingSheet: FC<BillingSheetProps> = ({ clubId, reason, initialMode = 'pay', onClose, onPaid }) => {
  const haptic = useHaptic();
  // Название клуба — он уже в кэше у формы встречи и у страницы управления.
  const clubName = useClubQuery(clubId).data?.name ?? '';
  const { sheetRef, dragHandlers } = useSheetDrag(onClose);
  const [mode, setMode] = useState<Mode>(initialMode);
  const [autopay, setAutopay] = useState(true);
  const [autopayTouched, setAutopayTouched] = useState(false);
  const [offerOpen, setOfferOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Снимок конца периода на момент ухода на оплату: «оплачено» = период сдвинулся, а не
  // «подписка и так была активна» (продление раньше срока).
  const periodEndAtStart = useRef<string | null | undefined>(undefined);
  const checkout = useStartCheckoutMutation();

  const billing = useBillingQuery(clubId, { refetchInterval: mode === 'waiting' ? POLL_INTERVAL_MS : false });
  const data = billing.data;
  const hasSubscription = data?.state === 'ACTIVE' || data?.state === 'GRACE' || data?.state === 'ENDED';
  // Оплата по СБП карту не сохраняет — ползунок недоступен до следующей оплаты картой.
  const autopayLocked = !!data && hasSubscription && !data.autopayPossible;
  const effectiveAutopay = autopayLocked ? false : autopay;

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  // Ползунок повторяет текущее положение на подписке, пока человек его не тронул.
  useEffect(() => {
    if (data && hasSubscription && !autopayTouched) setAutopay(data.autopay);
  }, [data, hasSubscription, autopayTouched]);

  // Ожидание: подтверждение видно по сдвигу периода и погашенному счёту.
  useEffect(() => {
    if (mode !== 'waiting' || !data) return;
    if (periodEndAtStart.current === undefined) {
      // Возврат из браузера без снимка: оплата прошла, если неоплаченных счетов у клуба не
      // осталось и период идёт. `pendingCheckout` считается по счетам ЛЮБОГО возраста (иначе
      // раннее продление с долгой оплатой показывало бы «Оплачено» со старой датой — ревью
      // 2026-09-07); пока счёт висит, ждём дальше.
      if (!data.pendingCheckout && data.state === 'ACTIVE') { setMode('paid'); haptic.notify('success'); }
      else periodEndAtStart.current = data.currentPeriodEnd;
      return;
    }
    if (!data.pendingCheckout && data.state === 'ACTIVE' && data.currentPeriodEnd !== periodEndAtStart.current) {
      setMode('paid');
      haptic.notify('success');
    }
  }, [mode, data, haptic]);

  useEffect(() => {
    if (mode !== 'waiting') return;
    const timer = window.setTimeout(() => setMode((m) => (m === 'waiting' ? 'timeout' : m)), POLL_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [mode]);

  const handlePay = () => {
    if (checkout.isPending) return;
    setError(null);
    haptic.impact('medium');
    periodEndAtStart.current = data?.currentPeriodEnd ?? null;
    checkout.mutate(
      { clubId, autopay: effectiveAutopay },
      {
        onSuccess: ({ paymentUrl }) => {
          openExternalLink(paymentUrl);
          setMode('waiting');
        },
        onError: (e) => {
          haptic.notify('error');
          setError(e instanceof Error ? e.message : 'Не удалось выставить счёт');
        },
      },
    );
  };

  const expired = reason === 'SUBSCRIPTION_EXPIRED' || data?.state === 'GRACE' || data?.state === 'ENDED';
  const title = mode === 'paid' ? 'Оплата за клуб' : expired ? 'Продлить подписку' : 'Оплата за клуб';
  const price = data ? formatRubles(data.priceKopecks) : null;
  // Списание — в день окончания оплаченного периода (PO 2026-09-07).
  const chargeDate = data?.currentPeriodEnd ? formatBillingDate(data.currentPeriodEnd) : null;
  const recipient = data?.recipientName ? `самозанятый ${data.recipientName}` : 'самозанятый';

  const renderPay = () => (
    <>
      {expired && data?.currentPeriodEnd && (
        <div className="rd-billing-note">
          Подписка за клуб <b>«{clubName}»</b> закончилась {formatBillingDate(data.currentPeriodEnd)}.
          {data.state === 'GRACE' && data.graceUntil
            ? ` До ${formatBillingDate(data.graceUntil)} всё работает, потом новые встречи — после оплаты.`
            : ' Начатые встречи доживут, новые — после оплаты.'}
        </div>
      )}
      <div className="rd-dues-amount">
        <span className="rd-dues-emoji" aria-hidden="true">💬</span>
        <span className="rd-dues-sum">{price ?? '…'}</span>
        <span className="rd-dues-per">/ мес · 30 дней с момента оплаты</span>
      </div>
      <div className="rd-billing-for">
        за клуб <b>«{clubName}»</b>
        {reason === 'FREE_MEETING_USED' && ' · первая встреча была бесплатной'}
      </div>

      <div className="rd-cl-feat" style={{ paddingTop: 2 }}>
        <div className="fi">
          <div className="ft">Продлевать автоматически</div>
          <div className="fd">
            {autopayLocked
              ? 'Прошлая оплата была по СБП — автопродление работает только для карт. Оплатите картой, и ползунок станет доступен.'
              : effectiveAutopay
                ? `Спишем ${price ?? ''} с этой же карты ${chargeDate ? chargeDate : 'в день окончания оплаченного периода'}. Отключить можно в любой момент на странице клуба.`
                : 'Напомним за 3 дня и за день до конца периода — оплатите вручную.'}
          </div>
        </div>
        <button
          type="button"
          className={`rd-cl-tgl${effectiveAutopay ? ' on' : ''}`}
          role="switch"
          aria-checked={effectiveAutopay}
          aria-label="Продлевать автоматически"
          disabled={autopayLocked}
          onClick={() => { haptic.select(); setAutopayTouched(true); setAutopay((v) => !v); }}
        />
      </div>

      <div className="rd-billing-prov">
        <div className="cap">Как проходит оплата</div>
        <div className="l"><span className="ic">🔒</span><span>Страница оплаты <b>Robokassa</b>: карта или СБП. Реквизиты карты мы не видим.</span></div>
        <div className="l"><span className="ic">🧾</span><span>Получатель — <b>{recipient}</b>, чек придёт на e-mail или в Telegram.</span></div>
        <button type="button" className="rd-billing-offer-btn" aria-expanded={offerOpen} onClick={() => setOfferOpen((v) => !v)}>
          {OFFER_TITLE} {offerOpen ? '▴' : '▾'}
        </button>
        {offerOpen && (
          <div className="rd-billing-offer">
            {offerParagraphs(data?.recipientName ?? '', price ?? '199 ₽').map((p) => <p key={p.slice(0, 12)}>{p}</p>)}
          </div>
        )}
      </div>

      {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}

      <button type="button" className="rd-btn-primary" disabled={!data || checkout.isPending} onClick={handlePay}>
        {checkout.isPending ? 'Выставляем счёт…' : hasSubscription ? `Продлить на месяц — ${price ?? ''}` : `Оплатить ${price ?? ''}`}
      </button>
      <div className="rd-cta-hint">Оплачивая, вы принимаете условия оферты. Откроется страница оплаты в браузере; после оплаты вернитесь в Telegram — кнопка будет на странице.</div>
    </>
  );

  // Кнопки «Закрыть» здесь нет намеренно (PO 2026-09-07): проверку не бросают, шит закрывается шапкой.
  const renderWaiting = () => (
    <div className="rd-billing-state">
      <div className="rd-billing-spin" aria-hidden="true" />
      <p className="t">Проверяем оплату…</p>
      <p className="d">
        Обычно это несколько секунд. Если оплата прошла, а мы всё ещё проверяем — не волнуйтесь:
        подтверждение придёт в личку от бота, встречу можно будет создать оттуда.
      </p>
    </div>
  );

  const renderTimeout = () => (
    <div className="rd-billing-state">
      <div className="ic" aria-hidden="true">⏳</div>
      <p className="t">Пока не видим оплату</p>
      <p className="d">
        Если она прошла, подтверждение придёт в личку от бота — обычно в течение пары минут.
        Если нет — попробуйте ещё раз.
      </p>
      <button type="button" className="rd-btn-outline" style={{ marginTop: 14 }} onClick={() => setMode('pay')}>Попробовать ещё раз</button>
    </div>
  );

  const renderPaid = () => (
    <div className="rd-billing-state">
      <div className="ic" aria-hidden="true">✅</div>
      <p className="t">Оплачено{data?.currentPeriodEnd ? ` до ${formatBillingDate(data.currentPeriodEnd)}` : ''}</p>
      <p className="d">
        {data?.autopay && data.autopayPossible
          ? `Автопродление включено: ${chargeDate ?? 'в день окончания периода'} спишем ${price ?? ''} с этой же карты. Отключить можно на странице клуба.`
          : 'Автопродление выключено: напомним за 3 дня и за день до конца периода.'}
      </p>
      <button type="button" className="rd-btn-primary" style={{ marginTop: 14 }} onClick={() => (onPaid ?? onClose)()}>
        {onPaid ? 'Вернуться к встрече' : 'Готово'}
      </button>
    </div>
  );

  return createPortal(
    <>
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet rd-sheet-in" role="dialog" aria-modal="true" aria-label="Оплата за клуб" ref={sheetRef}>
        <div className="rd-sheet-grip" {...dragHandlers}>
          <div className="rd-sheet-grabber" aria-hidden="true" />
          <div className="rd-sheet-head">
            <h2>{title}</h2>
            <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
          </div>
        </div>
        <div className="rd-sheet-body">
          {mode === 'pay' && renderPay()}
          {mode === 'waiting' && renderWaiting()}
          {mode === 'timeout' && renderTimeout()}
          {mode === 'paid' && renderPaid()}
        </div>
      </div>
    </>,
    document.body,
  );
};
