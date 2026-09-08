import { FC, CSSProperties, useState } from 'react';
import type { SkladchinaParticipantDto } from '../../types/api';

interface OrganizerParticipantListProps {
  participants: SkladchinaParticipantDto[];
  totalGoalKopecks: number | null;
  // A-2: организатор может отмечать/снимать отметку оплаты (только фиксированные режимы, активная складчина).
  canManagePayments?: boolean;
  busyUserId?: string | null;
  onMarkPaid?: (p: SkladchinaParticipantDto) => void;
  onUnmark?: (p: SkladchinaParticipantDto) => void;
  // V28/V29: организатор разрешает запрос участника на отказ. Отклонение (approve=false) несёт
  // обязательную причину — почему участник всё же должен заплатить.
  onResolveDecline?: (p: SkladchinaParticipantDto, approve: boolean, rejectReason?: string) => void;
  // V89: решение организатора по оплате — разбор присланного чека и сверка по ходу сбора.
  onResolvePayment?: (p: SkladchinaParticipantDto, accept: boolean) => void;
  // V89 (правка PO 2026-09-08): пока сбор идёт, заявку можно сверить сразу — кнопками в строке.
  canReviewPayments?: boolean;
}

const rowActionStyle: CSSProperties = {
  fontSize: 12,
  padding: '4px 10px',
  borderRadius: 8,
  border: '1px solid var(--text-faint)',
  background: 'transparent',
  color: 'var(--text)',
  cursor: 'pointer',
  fontFamily: 'inherit',
  whiteSpace: 'nowrap',
};

function getInitials(firstName: string, lastName: string | null): string {
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return `${firstName.charAt(0).toUpperCase()}${last}`;
}

function formatRubles(kopecks: number): string {
  return (Math.floor(kopecks / 100)).toLocaleString('ru-RU');
}

function statusBadge(status: string): { text: string; cls: string } {
  switch (status) {
    // До сверки «оплатил» — это заявка участника, а не подтверждённые деньги.
    case 'paid':                 return { text: 'Заявил оплату',  cls: 'rd-going' };
    case 'payment_confirmed':    return { text: 'Оплата сошлась', cls: 'rd-going' };
    case 'payment_rejected':     return { text: 'Платёж не дошёл', cls: 'rd-decline' };
    case 'payment_disputed':     return { text: 'Прислал чек',    cls: 'rd-warn' };
    case 'declined':             return { text: 'Отказался',      cls: 'rd-decline' };
    case 'expired_no_response':  return { text: 'Не ответил',     cls: 'rd-neutral2' };
    // Закрыто до дедлайна, пока участник ещё в статусе pending — нейтрально, без штрафа.
    case 'released':             return { text: 'Не потребовался', cls: 'rd-neutral2' };
    case 'pending':              return { text: 'Ожидает',        cls: 'rd-warn' };
    default:                     return { text: status,           cls: 'rd-warn' };
  }
}

export const OrganizerParticipantList: FC<OrganizerParticipantListProps> = ({
  participants,
  canManagePayments = false,
  busyUserId = null,
  onMarkPaid,
  onUnmark,
  onResolveDecline,
  onResolvePayment,
  canReviewPayments = false,
}) => {
  // V29: какая строка сейчас в режиме «отклонить с причиной» и её черновик причины.
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [rejectText, setRejectText] = useState('');

  const sorted = [...participants].sort((a, b) => {
    const order: Record<string, number> = {
      payment_disputed: 0, paid: 1, payment_confirmed: 2, pending: 3,
      payment_rejected: 4, declined: 5, released: 6, expired_no_response: 7,
    };
    return (order[a.status] ?? 99) - (order[b.status] ?? 99);
  });

  return (
    <>
      <div className="rd-section-sub-h">
        Участники <span className="rd-count">· {participants.length}</span>
      </div>
      <div className="rd-glass rd-rep-panel" style={{ marginBottom: 14 }}>
        {sorted.map((p) => {
          const badge = statusBadge(p.status);
          const showDeclared = p.declaredAmountKopecks != null;
          const showExpected = p.expectedAmountKopecks != null;
          const mismatch = showDeclared && showExpected
            && p.declaredAmountKopecks !== p.expectedAmountKopecks;
          const amounts = [
            showExpected ? `ожид. ${formatRubles(p.expectedAmountKopecks!)} ₽` : null,
            showDeclared ? `заявл. ${formatRubles(p.declaredAmountKopecks!)} ₽` : null,
          ].filter(Boolean).join(' · ');
          const busy = busyUserId === p.userId;
          const showDeclineRequest = !!onResolveDecline && p.declineRequested;
          // Спор с чеком разбирается прямо в строке — карточка ниже.
          const showDispute = !!onResolvePayment && p.status === 'payment_disputed';
          // Сверка по ходу сбора: заявленную оплату можно засчитать или отклонить сразу,
          // а уже вынесенное решение — переиграть, пока сбор не закрыт.
          const review = canReviewPayments && !!onResolvePayment && !showDeclineRequest &&
            (p.status === 'paid' || p.status === 'payment_confirmed' || p.status === 'payment_rejected')
            ? (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                {/* Отметку «получил наличкой» организатор мог поставить по ошибке — она снимается
                    отдельно от сверки: «не дошёл» звало бы человека прислать чек за чужую ошибку. */}
                {canManagePayments && p.status === 'paid' && (
                  <button
                    type="button"
                    style={{ ...rowActionStyle, color: 'var(--text-dim)' }}
                    disabled={busy}
                    onClick={() => onUnmark?.(p)}
                  >
                    {busy ? '…' : 'Отменить'}
                  </button>
                )}
                {p.status !== 'payment_confirmed' && (
                  <button
                    type="button"
                    style={rowActionStyle}
                    disabled={busy}
                    onClick={() => onResolvePayment(p, true)}
                  >
                    {busy ? '…' : 'Засчитать'}
                  </button>
                )}
                {p.status !== 'payment_rejected' && (
                  <button
                    type="button"
                    style={{ ...rowActionStyle, color: 'var(--danger)', borderColor: 'var(--danger)' }}
                    disabled={busy}
                    onClick={() => onResolvePayment(p, false)}
                  >
                    {busy ? '…' : 'Не дошёл'}
                  </button>
                )}
              </div>
            )
            : null;
          // A-2: pending → «Отметить оплату»; paid → «Отменить». Если у участника открыт запрос
          // на отказ, вместо кнопки отметки показываются элементы управления запросом ниже.
          const action = review ?? (
            !canManagePayments || showDeclineRequest ? null
            : p.status === 'pending' ? (
              <button type="button" style={rowActionStyle} disabled={busy} onClick={() => onMarkPaid?.(p)}>
                {busy ? '…' : 'Отметить оплату'}
              </button>
            ) : null);
          return (
            <div key={p.userId}>
              <div className="rd-rep-row" style={{ cursor: 'default' }}>
                <span className="rd-ico">
                  {p.avatarUrl
                    ? <img src={p.avatarUrl} alt="" />
                    : getInitials(p.firstName, p.lastName)}
                </span>
                <div className="rd-info">
                  <div className="rd-ttl">
                    {p.firstName}{p.lastName ? ` ${p.lastName}` : ''}
                  </div>
                  {amounts && (
                    <div className="rd-met" style={mismatch ? { color: 'var(--danger)' } : undefined}>
                      {amounts}
                    </div>
                  )}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 6 }}>
                  <span className={`rd-badge ${showDeclineRequest ? 'rd-warn' : badge.cls}`}>
                    {showDeclineRequest ? 'Просит отказаться' : badge.text}
                  </span>
                  {action}
                </div>
              </div>
              {showDispute && (
                <div style={{ padding: '0 0 12px 46px' }}>
                  {p.receiptNote && (
                    <div className="rd-met" style={{ marginBottom: 8 }}>«{p.receiptNote}»</div>
                  )}
                  {p.receiptUrl && (
                    <a href={p.receiptUrl} target="_blank" rel="noopener noreferrer">
                      <img
                        src={p.receiptUrl}
                        alt={`Чек от ${p.firstName}`}
                        style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 10, display: 'block' }}
                      />
                    </a>
                  )}
                  <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                    <button type="button" style={rowActionStyle} disabled={busy} onClick={() => onResolvePayment!(p, true)}>
                      {busy ? '…' : 'Всё сошлось — засчитать'}
                    </button>
                    <button
                      type="button"
                      style={{ ...rowActionStyle, color: 'var(--danger)', borderColor: 'var(--danger)' }}
                      disabled={busy}
                      onClick={() => onResolvePayment!(p, false)}
                    >
                      {busy ? '…' : 'Платежа нет'}
                    </button>
                  </div>
                </div>
              )}
              {p.status === 'payment_rejected' && p.paymentRejectNote && (
                <div className="rd-met" style={{ padding: '0 0 10px 46px' }}>«{p.paymentRejectNote}»</div>
              )}
              {showDeclineRequest && (
                <div style={{ padding: '0 0 10px 46px' }}>
                  {p.declineNote && (
                    <div className="rd-met" style={{ marginBottom: 8 }}>«{p.declineNote}»</div>
                  )}
                  {rejectingId === p.userId ? (
                    <div>
                      <textarea
                        className="rd-textarea"
                        rows={2}
                        placeholder="Почему участник должен оплатить (обязательно)"
                        value={rejectText}
                        onChange={(e) => setRejectText(e.target.value)}
                        maxLength={500}
                      />
                      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                        <button
                          type="button"
                          style={rowActionStyle}
                          disabled={busy || !rejectText.trim()}
                          onClick={() => { onResolveDecline!(p, false, rejectText); setRejectingId(null); setRejectText(''); }}
                        >
                          {busy ? '…' : 'Отклонить заявку'}
                        </button>
                        <button
                          type="button"
                          style={{ ...rowActionStyle, color: 'var(--text-dim)' }}
                          onClick={() => { setRejectingId(null); setRejectText(''); }}
                        >
                          Отмена
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div style={{ display: 'flex', gap: 8 }}>
                      <button type="button" style={rowActionStyle} disabled={busy} onClick={() => onResolveDecline!(p, true)}>
                        {busy ? '…' : 'Одобрить отказ'}
                      </button>
                      <button
                        type="button"
                        style={{ ...rowActionStyle, color: 'var(--text-dim)' }}
                        disabled={busy}
                        onClick={() => { setRejectingId(p.userId); setRejectText(''); }}
                      >
                        Отклонить
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </>
  );
};
