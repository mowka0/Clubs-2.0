import { FC, CSSProperties } from 'react';
import type { SkladchinaParticipantDto } from '../../types/api';

interface OrganizerParticipantListProps {
  participants: SkladchinaParticipantDto[];
  totalGoalKopecks: number | null;
  // A-2: organizer can mark/unmark payments (fixed modes, active skladchina only).
  canManagePayments?: boolean;
  busyUserId?: string | null;
  onMarkPaid?: (p: SkladchinaParticipantDto) => void;
  onUnmark?: (p: SkladchinaParticipantDto) => void;
  // V28: organizer resolves a participant's decline request.
  onResolveDecline?: (p: SkladchinaParticipantDto, approve: boolean) => void;
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
    case 'paid':                 return { text: 'Оплатил',        cls: 'rd-going' };
    case 'declined':             return { text: 'Отказался',      cls: 'rd-decline' };
    case 'expired_no_response':  return { text: 'Не ответил',     cls: 'rd-neutral2' };
    // Closed before the deadline while still pending — neutral, no penalty.
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
}) => {
  const sorted = [...participants].sort((a, b) => {
    const order: Record<string, number> = { paid: 0, pending: 1, declined: 2, released: 3, expired_no_response: 4 };
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
          // A-2: pending → "Отметить оплату"; paid → "Отменить". A participant with an open decline
          // request shows the request controls below instead of the mark button.
          const action = !canManagePayments || showDeclineRequest ? null
            : p.status === 'pending' ? (
              <button type="button" style={rowActionStyle} disabled={busy} onClick={() => onMarkPaid?.(p)}>
                {busy ? '…' : 'Отметить оплату'}
              </button>
            ) : p.status === 'paid' ? (
              <button
                type="button"
                style={{ ...rowActionStyle, color: 'var(--text-dim)' }}
                disabled={busy}
                onClick={() => onUnmark?.(p)}
              >
                {busy ? '…' : 'Отменить'}
              </button>
            ) : null;
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
              {showDeclineRequest && (
                <div style={{ padding: '0 0 10px 46px' }}>
                  {p.declineNote && (
                    <div className="rd-met" style={{ marginBottom: 8 }}>«{p.declineNote}»</div>
                  )}
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button type="button" style={rowActionStyle} disabled={busy} onClick={() => onResolveDecline!(p, true)}>
                      {busy ? '…' : 'Одобрить отказ'}
                    </button>
                    <button
                      type="button"
                      style={{ ...rowActionStyle, color: 'var(--text-dim)' }}
                      disabled={busy}
                      onClick={() => onResolveDecline!(p, false)}
                    >
                      Отклонить
                    </button>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </>
  );
};
