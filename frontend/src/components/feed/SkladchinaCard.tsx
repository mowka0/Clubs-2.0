import { FC } from 'react';
import type { MySkladchinaListItemDto } from '../../types/api';
import { formatRub } from '../../utils/money';
import { DATE_FMT, KIND_LABEL, initials, statusLabel } from '../../utils/skladchinaKind';

interface SkladchinaCardProps {
  skladchina: MySkladchinaListItemDto;
  onClick: () => void;
}

interface Badge {
  text: string;
  accent: boolean;
}

function pickBadge(s: MySkladchinaListItemDto): Badge | null {
  // У закрытых — итог, а не персональное состояние.
  if (s.status !== 'active') return { text: statusLabel(s.status), accent: false };
  if (s.actionRequired) return { text: 'Ждёт вас', accent: true };
  switch (s.myDebtStatus) {
    case 'received': return { text: 'Оплачено', accent: false };
    case 'claimed': return { text: 'Ждём подтверждения', accent: false };
    case 'promised': return { text: 'Обещали', accent: false };
    case 'forgiven': return { text: 'Прощён', accent: false };
    case 'dropped': return { text: 'Выбыли', accent: false };
    default: break;
  }
  if (s.isCreator) return { text: 'Ваш сбор', accent: false };
  return null;
}

export const SkladchinaCard: FC<SkladchinaCardProps> = ({ skladchina, onClick }) => {
  const badge = pickBadge(skladchina);
  const clubInitials = initials(skladchina.clubName);
  const target = skladchina.targetKopecks ?? skladchina.amountKopecks;
  const moneyPct = target && target > 0 ? Math.min(100, Math.round((skladchina.receivedKopecks / target) * 100)) : 0;
  const moneyLine = target && target > 0
    ? `${formatRub(skladchina.receivedKopecks)} из ${formatRub(target)}`
    : `${formatRub(skladchina.receivedKopecks)} получено`;
  const deadlineLine = skladchina.deadline ? `до ${DATE_FMT.format(new Date(skladchina.deadline))}` : 'без срока';

  return (
    <button type="button" className="rd-activity-card" onClick={onClick}>
      <div className="rd-act-cover rd-c-coin">
        <span className="rd-type-badge">{KIND_LABEL[skladchina.kind].toUpperCase()}</span>
      </div>
      <div className="rd-act-body">
        <div className="rd-act-club-row">
          <span className="rd-club-avt">
            {skladchina.clubAvatarUrl ? <img src={skladchina.clubAvatarUrl} alt="" /> : clubInitials}
          </span>
          <span>{skladchina.clubName}</span>
        </div>
        <div className="rd-act-ttl">{skladchina.title}</div>
        <div className="rd-act-meta" style={{ fontWeight: 600, color: 'var(--text)' }}>
          Оплатили {skladchina.receivedCount} из {skladchina.debtCount}
        </div>
        <div className="rd-progress" style={{ marginTop: 8 }} aria-hidden="true">
          <span className="rd-fill" style={{ width: `${moneyPct}%`, display: 'block', height: '100%' }} />
        </div>
        <div className="rd-act-meta">
          {moneyLine} · {deadlineLine}
        </div>
        {badge && (
          <div className="rd-badges-row">
            <span className={`rd-badge ${badge.accent ? 'rd-warn' : 'rd-neutral'}`}>{badge.text}</span>
          </div>
        )}
      </div>
    </button>
  );
};
