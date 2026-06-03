import { FC } from 'react';
import type { MySkladchinaListItemDto } from '../../types/api';

interface SkladchinaCardProps {
  skladchina: MySkladchinaListItemDto;
  onClick: () => void;
}

interface Badge {
  text: string;
  accent: boolean;
}

const DEADLINE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  hour: '2-digit',
  minute: '2-digit',
});

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

function formatRubles(kopecks: number): string {
  const rub = Math.floor(kopecks / 100);
  return rub.toLocaleString('ru-RU');
}

function pickBadge(s: MySkladchinaListItemDto): Badge | null {
  // For closed skladchinas surface the final status, not personal myStatus
  if (s.status !== 'active') {
    switch (s.status) {
      case 'closed_success': return { text: 'Завершён', accent: false };
      case 'closed_failed':  return { text: 'Не собран', accent: false };
      case 'cancelled':      return { text: 'Отменён',  accent: false };
    }
  }
  if (s.actionRequired) return { text: 'Требует оплаты', accent: true };
  switch (s.myStatus) {
    case 'paid':                 return { text: 'Оплачено', accent: false };
    case 'declined':             return { text: 'Отказался', accent: false };
    case 'expired_no_response':  return { text: 'Не успел', accent: false };
    default:                     break;
  }
  if (s.isOrganizerView) return { text: 'Ваш сбор', accent: false };
  return null;
}

export const SkladchinaCard: FC<SkladchinaCardProps> = ({ skladchina, onClick }) => {
  const badge = pickBadge(skladchina);
  const clubInitials = getInitials(skladchina.clubName);
  const deadlineStr = DEADLINE_FMT.format(new Date(skladchina.deadline));

  const hasGoal = skladchina.totalGoalKopecks != null && skladchina.totalGoalKopecks > 0;
  const percent = hasGoal
    ? Math.min(100, Math.round((skladchina.collectedKopecks / skladchina.totalGoalKopecks!) * 100))
    : null;

  const amounts = hasGoal
    ? `${formatRubles(skladchina.collectedKopecks)} ₽ / ${formatRubles(skladchina.totalGoalKopecks!)} ₽${percent !== null ? ` · ${percent}%` : ''}`
    : `${formatRubles(skladchina.collectedKopecks)} ₽ собрано (по желанию)`;

  return (
    <button type="button" className="rd-activity-card" onClick={onClick}>
      <div className="rd-act-cover rd-c-coin">
        <span className="rd-type-badge">СБОР</span>
      </div>
      <div className="rd-act-body">
        <div className="rd-act-club-row">
          <span className="rd-club-avt">
            {skladchina.clubAvatarUrl ? <img src={skladchina.clubAvatarUrl} alt="" /> : clubInitials}
          </span>
          <span>{skladchina.clubName}</span>
        </div>
        <div className="rd-act-ttl">{skladchina.title}</div>
        <div className="rd-act-meta">{amounts}</div>
        {hasGoal && (
          <div className="rd-progress" style={{ marginTop: 8 }} aria-hidden="true">
            <span className="rd-fill" style={{ width: `${percent}%`, display: 'block', height: '100%' }} />
          </div>
        )}
        <div className="rd-act-meta">
          До {deadlineStr} · {skladchina.paidCount}/{skladchina.participantCount} оплатили
        </div>
        {(badge || skladchina.affectsReputation) && (
          <div className="rd-badges-row">
            {badge && <span className={`rd-badge ${badge.accent ? 'rd-warn' : 'rd-neutral'}`}>{badge.text}</span>}
            {skladchina.affectsReputation && (
              <span className="rd-badge rd-rep" title="Влияет на репутацию">⚠️ Репутация</span>
            )}
          </div>
        )}
      </div>
    </button>
  );
};
