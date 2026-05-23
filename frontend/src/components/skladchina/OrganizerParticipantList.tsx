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

function statusLabel(status: string): { text: string; cls: string } {
  switch (status) {
    case 'paid':                 return { text: 'Оплатил',   cls: 'paid' };
    case 'declined':             return { text: 'Отказался', cls: 'declined' };
    case 'expired_no_response':  return { text: 'Не ответил', cls: 'expired' };
    case 'pending':              return { text: 'Ожидает',   cls: 'pending' };
    default:                     return { text: status,      cls: 'pending' };
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
    <div className="sklad-participants">
      <div className="sklad-block-title">
        Участники <span className="count">· {participants.length}</span>
      </div>
      <div className="sklad-participant-list">
        {sorted.map((p) => {
          const label = statusLabel(p.status);
          const showDeclared = p.declaredAmountKopecks != null;
          const showExpected = p.expectedAmountKopecks != null;
          const mismatch = showDeclared && showExpected
            && p.declaredAmountKopecks !== p.expectedAmountKopecks;
          return (
            <div key={p.userId} className="sklad-participant-row">
              <span className="avt">
                {p.avatarUrl
                  ? <img src={p.avatarUrl} alt="" />
                  : getInitials(p.firstName, p.lastName)}
              </span>
              <div className="body">
                <div className="name">
                  {p.firstName}{p.lastName ? ` ${p.lastName}` : ''}
                </div>
                <div className="amounts">
                  {showExpected && (
                    <span className="expected">
                      Ожидается: {formatRubles(p.expectedAmountKopecks!)} ₽
                    </span>
                  )}
                  {showDeclared && (
                    <span className={mismatch ? 'declared mismatch' : 'declared'}>
                      Заявлено: {formatRubles(p.declaredAmountKopecks!)} ₽
                    </span>
                  )}
                </div>
              </div>
              <span className={`status status-${label.cls}`}>{label.text}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
