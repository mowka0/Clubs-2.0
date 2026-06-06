import { FC } from 'react';
import type { SkladchinaParticipantDto } from '../../types/api';

interface OrganizerParticipantListProps {
  participants: SkladchinaParticipantDto[];
  totalGoalKopecks: number | null;
}

function getInitials(firstName: string, lastName: string | null): string {
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return `${firstName.charAt(0).toUpperCase()}${last}`;
}

function formatRubles(kopecks: number): string {
  return (Math.floor(kopecks / 100)).toLocaleString('ru-RU');
}

function statusBadge(status: string): { text: string; cls: string } {
  switch (status) {
    case 'paid':                 return { text: 'Оплатил',    cls: 'rd-going' };
    case 'declined':             return { text: 'Отказался',  cls: 'rd-decline' };
    case 'expired_no_response':  return { text: 'Не ответил', cls: 'rd-neutral2' };
    case 'pending':              return { text: 'Ожидает',    cls: 'rd-warn' };
    default:                     return { text: status,       cls: 'rd-warn' };
  }
}

export const OrganizerParticipantList: FC<OrganizerParticipantListProps> = ({
  participants,
}) => {
  const sorted = [...participants].sort((a, b) => {
    const order: Record<string, number> = { paid: 0, pending: 1, declined: 2, expired_no_response: 3 };
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
          return (
            <div key={p.userId} className="rd-rep-row" style={{ cursor: 'default' }}>
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
              <span className={`rd-badge ${badge.cls}`}>{badge.text}</span>
            </div>
          );
        })}
      </div>
    </>
  );
};
