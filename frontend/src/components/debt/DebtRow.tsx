import { FC, ReactNode, useState } from 'react';
import { ConfirmSheet } from '../ConfirmSheet';
import { ImageLightbox } from '../ImageLightbox';
import { PhotoAttach } from '../PhotoAttach';
import type { DebtAction } from '../../queries/debts';
import type { DebtDto } from '../../types/api';
import { formatRub } from '../../utils/money';
import { DATE_FMT, DAY_FMT, defaultPromiseDate, initials, personName } from '../../utils/skladchinaKind';

interface DebtRowProps {
  debt: DebtDto;
  /** Кто смотрит: определяет сторону и, значит, набор кнопок. */
  viewerId: string;
  /** Показывать «за что» (сбор · клуб) — в паре; на странице сбора лишнее. */
  showContext?: boolean;
  /** Кнопок нет: сбор закрыт, сальдо в подтверждении, смотрит третья сторона. */
  readOnly?: boolean;
  busy?: boolean;
  onAction: (action: DebtAction) => void;
  /** Кнопка сбора в том же ряду (например, «Передумал» у «Кто берёт?»), чтобы не переносить строку. */
  extraAction?: ReactNode;
}

type Inline = 'none' | 'promise' | 'reject' | 'note';

/**
 * Строка долга — одна на экран сбора, пару и список создателя (skladchina-v3 § 9). Кнопки по
 * направлению: должнику «Отдал» / «Оплачу позже» / «Не согласен», получателю «Получил» /
 * «Не получил» / «Простить». Слова «свести», «засчитать», «не дошёл» не используются.
 */
export const DebtRow: FC<DebtRowProps> = ({ debt, viewerId, showContext = false, readOnly = false, busy = false, onAction, extraAction }) => {
  const [inline, setInline] = useState<Inline>('none');
  const [promiseDate, setPromiseDate] = useState(defaultPromiseDate);
  const [text, setText] = useState('');
  const [receiptDraft, setReceiptDraft] = useState<string | null>(null);
  const [claimAsk, setClaimAsk] = useState(false);
  const [forgiveAsk, setForgiveAsk] = useState(false);
  const [receiptOpen, setReceiptOpen] = useState(false);

  const isDebtor = debt.debtor.id === viewerId;
  const isCreditor = debt.creditor.id === viewerId;
  const own = debt.debtor.id === debt.creditor.id;
  const counterparty = isDebtor ? debt.creditor : debt.debtor;
  const open = debt.status === 'waiting' || debt.status === 'promised' || debt.status === 'claimed';
  const inSettlement = debt.settlementId !== null;
  const canAct = !readOnly && !busy && open && !inSettlement && !own && (isDebtor || isCreditor);

  const statusLine = (() => {
    if (own) return 'ваш взнос ✅';
    switch (debt.status) {
      case 'waiting':
        if (debt.rejectedAt) return 'не получил · ответьте: заметка или чек';
        if (!debt.dueAt) return 'без срока';
        return debt.isOverdue ? `срок вышел ${DAY_FMT.format(new Date(debt.dueAt))}` : `до ${DATE_FMT.format(new Date(debt.dueAt))}`;
      case 'promised':
        return `обещал к ${debt.promisedAt ? DAY_FMT.format(new Date(debt.promisedAt)) : '—'}`;
      case 'claimed':
        if (inSettlement) return 'в сальдо · ждёт подтверждения';
        return isCreditor ? 'говорит, что отдал · подтвердите' : 'ждём подтверждения';
      case 'received': return 'получено ✅';
      case 'forgiven': return 'прощён';
      case 'dropped': return 'выбыл';
    }
  })();

  // «Отдал» необратим (решение PO 2026-09-13: отменять перевод нечего), поэтому спрашиваем до отправки.
  const claimText = `Отдали ${formatRub(debt.amountKopecks)}? ${debt.creditor.firstName} получит уведомление и подтвердит.`;

  const submitInline = () => {
    const note = text.trim();
    switch (inline) {
      case 'promise':
        onAction({ type: 'promise', date: promiseDate });
        break;
      case 'reject':
        onAction({ type: 'reject', note: note || null });
        break;
      case 'note':
        if (!note && !receiptDraft) return;
        onAction({ type: 'note', note: note || null, receiptUrl: receiptDraft });
        break;
      default:
        return;
    }
    setInline('none');
    setText('');
    setReceiptDraft(null);
  };

  const nameLine = personName(counterparty);
  // Разбор по долгу: «Не получил» получателя и ответ должника — как реплики, с автором.
  const thread = [
    debt.rejectedAt && {
      key: 'reject',
      author: debt.creditor,
      when: DAY_FMT.format(new Date(debt.rejectedAt)),
      text: `Не получил${debt.rejectNote ? `: «${debt.rejectNote}»` : ''}`,
      receiptUrl: null as string | null,
    },
    (debt.note || debt.receiptUrl) && {
      key: 'reply',
      author: debt.debtor,
      when: null as string | null,
      text: debt.note ? `«${debt.note}»` : 'Приложил чек',
      receiptUrl: debt.receiptUrl,
    },
  ].filter((m): m is Exclude<typeof m, false | null | '' | undefined> => Boolean(m));

  return (
    <div className={`rd-debt-row${debt.status === 'received' ? ' rd-debt-done' : ''}`} data-testid={`debt-${debt.id}`}>
      <div className="rd-debt-head">
        <span className="rd-av rd-debt-av">
          {counterparty.avatarUrl ? <img src={counterparty.avatarUrl} alt="" /> : initials(nameLine)}
        </span>
        <span className="rd-debt-who">
          <b>{nameLine}</b>
          {counterparty.username && <span className="rd-debt-handle">@{counterparty.username}</span>}
        </span>
        <span className="rd-debt-amount">
          {debt.quantity > 1 && <span className="rd-debt-qty">{debt.quantity} шт. · </span>}
          {formatRub(debt.amountKopecks)}
        </span>
      </div>
      {showContext && <div className="rd-debt-meta">{debt.skladchinaTitle} · {debt.clubName}</div>}
      <div className={`rd-debt-meta${debt.isOverdue && open ? ' rd-debt-overdue' : ''}`}>{statusLine}</div>
      {thread.length > 0 && (
        <div className="rd-debt-thread">
          {thread.map((m) => (
            <div className="rd-debt-msg" key={m.key}>
              <span className="rd-av rd-debt-av">
                {m.author.avatarUrl ? <img src={m.author.avatarUrl} alt="" /> : initials(personName(m.author))}
              </span>
              <div className="rd-debt-msg-body">
                <div className="rd-debt-msg-who">
                  {m.author.id === viewerId ? 'Вы' : m.author.firstName}{m.when && <span> · {m.when}</span>}
                </div>
                <div className="rd-debt-msg-text">{m.text}</div>
                {m.receiptUrl && (
                  <button type="button" className="rd-debt-msg-receipt" aria-label="Открыть чек" onClick={() => setReceiptOpen(true)}>
                    <img src={m.receiptUrl} alt="Чек" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {canAct && inline === 'none' && (
        <div className="rd-debt-actions">
          {isDebtor && (debt.status === 'waiting' || debt.status === 'promised') && (
            <>
              <button type="button" className="rd-btn-primary" onClick={() => setClaimAsk(true)}>Отдал</button>
              <button type="button" className="rd-btn-outline" onClick={() => setInline('promise')}>Оплачу позже</button>
              {/* «Кто берёт?»: человек сам нажал «Беру» и может «Передумать» до заказа — спорить не с чем;
                  кнопка остаётся только как ответ на «Не получил» (заметка и чек). */}
              {(debt.skladchinaKind !== 'per_head' || debt.rejectedAt) && (
                <button type="button" className="rd-btn-outline" onClick={() => setInline('note')}>
                  {debt.rejectedAt ? 'Ответить' : 'Не согласен'}
                </button>
              )}
              {extraAction}
            </>
          )}
          {isCreditor && (
            <>
              <button type="button" className="rd-btn-primary" onClick={() => onAction({ type: 'confirm' })}>Получил</button>
              {debt.status === 'claimed' && (
                <button type="button" className="rd-btn-outline" onClick={() => setInline('reject')}>Не получил</button>
              )}
              <button type="button" className="rd-ghost-btn" onClick={() => setForgiveAsk(true)}>Простить</button>
            </>
          )}
        </div>
      )}

      {canAct && inline === 'promise' && (
        <div className="rd-debt-inline">
          <input className="rd-input" type="date" value={promiseDate} onChange={(e) => setPromiseDate(e.target.value)} aria-label="Дата обещания" />
          <div className="rd-form-actions">
            <button type="button" className="rd-btn-primary" onClick={submitInline}>Обещаю</button>
            <button type="button" className="rd-btn-outline" onClick={() => setInline('none')}>Отмена</button>
          </div>
        </div>
      )}

      {canAct && (inline === 'reject' || inline === 'note') && (
        <div className="rd-debt-inline">
          <textarea
            className="rd-textarea"
            rows={2}
            maxLength={500}
            placeholder={inline === 'reject' ? 'Заметка (необязательно): в выписке нет…' : 'Что не так? Например: сумма больше, чем договаривались'}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          {inline === 'note' && <PhotoAttach value={receiptDraft} onChange={setReceiptDraft} addLabel="Приложить чек" />}
          <div className="rd-form-actions">
            <button type="button" className="rd-btn-primary" onClick={submitInline} disabled={inline === 'note' && !text.trim() && !receiptDraft}>
              {inline === 'reject' ? 'Не получил' : 'Отправить'}
            </button>
            <button type="button" className="rd-btn-outline" onClick={() => { setInline('none'); setText(''); setReceiptDraft(null); }}>Отмена</button>
          </div>
        </div>
      )}

      {claimAsk && (
        <ConfirmSheet text={claimText} onConfirm={() => { setClaimAsk(false); onAction({ type: 'claim' }); }} onCancel={() => setClaimAsk(false)} />
      )}
      {forgiveAsk && (
        <ConfirmSheet
          text={`Простить ${formatRub(debt.amountKopecks)} ${debt.debtor.firstName}? Долг закроется без денег, вернуть нельзя.`}
          confirmLabel="Простить"
          onConfirm={() => { setForgiveAsk(false); onAction({ type: 'forgive' }); }}
          onCancel={() => setForgiveAsk(false)}
        />
      )}
      <ImageLightbox src={receiptOpen ? debt.receiptUrl : null} alt="Чек" onClose={() => setReceiptOpen(false)} />
    </div>
  );
};
