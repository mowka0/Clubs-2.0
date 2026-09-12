import { FC, useState } from 'react';
import { PhotoAttach } from '../PhotoAttach';
import type { DebtAction } from '../../queries/debts';
import type { DebtDto } from '../../types/api';
import { formatRub } from '../../utils/money';
import { DAY_FMT, DATE_FMT, initials, personName } from '../../utils/skladchinaKind';

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
}

type Inline = 'none' | 'promise' | 'reject' | 'note' | 'receipt';

function defaultPromiseDate(): string {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  return d.toISOString().slice(0, 10);
}

/**
 * Строка долга — одна на экран сбора, пару и список создателя (skladchina-v3 § 9). Кнопки по
 * направлению: должнику «Отдал» / «Оплачу позже» / «Не согласен», получателю «Получил» /
 * «Не получил» / «Простить». Слова «свести», «засчитать», «не дошёл» не используются.
 */
export const DebtRow: FC<DebtRowProps> = ({ debt, viewerId, showContext = false, readOnly = false, busy = false, onAction }) => {
  const [inline, setInline] = useState<Inline>('none');
  const [promiseDate, setPromiseDate] = useState(defaultPromiseDate);
  const [text, setText] = useState('');

  const isDebtor = debt.debtor.id === viewerId;
  const isCreditor = debt.creditor.id === viewerId;
  const own = debt.debtor.id === debt.creditor.id;
  const counterparty = isDebtor ? debt.creditor : debt.debtor;
  const open = debt.status === 'waiting' || debt.status === 'promised' || debt.status === 'claimed';
  const inSettlement = debt.settlementId !== null;
  const canAct = !readOnly && !busy && open && !inSettlement && !own && (isDebtor || isCreditor);

  const statusLine = (() => {
    if (own) return 'своя доля';
    switch (debt.status) {
      case 'waiting':
        if (debt.rejectedAt) return `не получил${debt.rejectNote ? `: «${debt.rejectNote}»` : ''} · приложите чек`;
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
        if (!note) return;
        onAction({ type: 'note', note });
        break;
      default:
        return;
    }
    setInline('none');
    setText('');
  };

  const nameLine = personName(counterparty);

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
        <span className="rd-debt-amount">{formatRub(debt.amountKopecks)}</span>
      </div>
      {showContext && <div className="rd-debt-meta">{debt.skladchinaTitle} · {debt.clubName}</div>}
      <div className={`rd-debt-meta${debt.isOverdue && open ? ' rd-debt-overdue' : ''}`}>{statusLine}</div>
      {debt.note && <div className="rd-debt-meta">Заметка: «{debt.note}»</div>}
      {debt.receiptUrl && (
        <a className="rd-debt-receipt" href={debt.receiptUrl} target="_blank" rel="noopener noreferrer">
          Чек приложен ›
        </a>
      )}

      {canAct && inline === 'none' && (
        <div className="rd-debt-actions">
          {isDebtor && (debt.status === 'waiting' || debt.status === 'promised') && (
            <>
              <button type="button" className="rd-btn-primary" onClick={() => onAction({ type: 'claim' })}>Отдал</button>
              <button type="button" className="rd-btn-outline" onClick={() => setInline('promise')}>Оплачу позже</button>
              {debt.rejectedAt && (
                <button type="button" className="rd-btn-outline" onClick={() => setInline('receipt')}>Приложить чек</button>
              )}
              <button type="button" className="rd-ghost-btn" onClick={() => setInline('note')}>Не согласен</button>
            </>
          )}
          {isDebtor && debt.status === 'claimed' && (
            <button type="button" className="rd-btn-outline" onClick={() => onAction({ type: 'unclaim' })}>Отменить</button>
          )}
          {isCreditor && (
            <>
              <button type="button" className="rd-btn-primary" onClick={() => onAction({ type: 'confirm' })}>Получил</button>
              {debt.status === 'claimed' && (
                <button type="button" className="rd-btn-outline" onClick={() => setInline('reject')}>Не получил</button>
              )}
              <button type="button" className="rd-ghost-btn" onClick={() => onAction({ type: 'forgive' })}>Простить</button>
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
          <div className="rd-form-actions">
            <button type="button" className="rd-btn-primary" onClick={submitInline}>
              {inline === 'reject' ? 'Не получил' : 'Отправить'}
            </button>
            <button type="button" className="rd-btn-outline" onClick={() => { setInline('none'); setText(''); }}>Отмена</button>
          </div>
        </div>
      )}

      {canAct && inline === 'receipt' && (
        <div className="rd-debt-inline">
          <PhotoAttach
            value={null}
            onChange={(url) => {
              if (url) onAction({ type: 'receipt', url });
              setInline('none');
            }}
            addLabel="Загрузить чек"
          />
          <button type="button" className="rd-ghost-btn" onClick={() => setInline('none')}>Отмена</button>
        </div>
      )}
    </div>
  );
};
