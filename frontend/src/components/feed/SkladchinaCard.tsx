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

  const isClosed = skladchina.status !== 'active';
  const cardClass = isClosed ? 'skladchina-card closed' : 'skladchina-card';

  return (
    <button type="button" className={cardClass} onClick={onClick}>
      <div className="skladchina-card-body">
        <div className="title">{skladchina.title}</div>
        <div className="club">
          <span className="club-avt">
            {skladchina.clubAvatarUrl
              ? <img src={skladchina.clubAvatarUrl} alt="" />
              : clubInitials}
          </span>
          <span className="club-name">{skladchina.clubName}</span>
        </div>
        <div className="amounts">
          {hasGoal ? (
            <>
              <span className="collected">{formatRubles(skladchina.collectedKopecks)} ₽</span>
              <span className="sep">/</span>
              <span className="goal">{formatRubles(skladchina.totalGoalKopecks!)} ₽</span>
              {percent !== null && <span className="percent">{percent}%</span>}
            </>
          ) : (
            <>
              <span className="collected">{formatRubles(skladchina.collectedKopecks)} ₽</span>
              <span className="voluntary-note">собрано (по желанию)</span>
            </>
          )}
        </div>
        {hasGoal && (
          <div className="progress-bar">
            <div className="fill" style={{ width: `${percent}%` }} />
          </div>
        )}
        <div className="footer-row">
          {badge && (
            <span className={badge.accent ? 'skladchina-badge accent' : 'skladchina-badge'}>
              {badge.text}
            </span>
          )}
          {skladchina.affectsReputation && (
            <span className="skladchina-badge reputation" title="Влияет на репутацию">
              ⚠️ Репутация
            </span>
          )}
          <span className="stats">
            До {deadlineStr} · {skladchina.paidCount}/{skladchina.participantCount} оплатили
          </span>
        </div>
      </div>
    </button>
  );
};
