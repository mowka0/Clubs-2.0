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
  // V89: сбор уже закрыт, но организатор не нашёл платёж и окно на чек ещё открыто — это
  // срочнее любого итога сбора, поэтому проверяется до финального статуса.
  if (s.myStatus === 'payment_rejected' && s.actionRequired) return { text: 'Нужен чек', accent: true };
  // Для закрытых складчин показываем финальный статус, а не персональный myStatus
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
    case 'payment_confirmed':    return { text: 'Оплата сошлась', accent: false };
    case 'payment_disputed':     return { text: 'Чек на проверке', accent: false };
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
  // A-5: прогресс по людям — главная строка; деньги — приглушённая вторичная строка.
  const peoplePercent = skladchina.participantCount > 0
    ? Math.round((skladchina.paidCount / skladchina.participantCount) * 100)
    : 0;
  // V89: сверенная организатором доля — зелёная голова полосы.
  const confirmedPercent = skladchina.participantCount > 0
    ? Math.round((skladchina.confirmedCount / skladchina.participantCount) * 100)
    : 0;
  const moneyLine = hasGoal
    ? `${formatRubles(skladchina.collectedKopecks)} ₽ из ${formatRubles(skladchina.totalGoalKopecks!)} ₽`
    : `${formatRubles(skladchina.collectedKopecks)} ₽ собрано`;

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
        <div className="rd-act-meta" style={{ fontWeight: 600, color: 'var(--text)' }}>
          Скинулись {skladchina.paidCount} из {skladchina.participantCount}
        </div>
        {/* Две доли, как на странице сбора: сверенное организатором зелёным, заявленное — серым. */}
        <div className="rd-progress rd-split" style={{ marginTop: 8 }} aria-hidden="true">
          <span className="rd-fill rd-fill-confirmed" style={{ width: `${confirmedPercent}%` }} />
          <span className="rd-fill rd-fill-claimed" style={{ width: `${Math.max(0, peoplePercent - confirmedPercent)}%` }} />
        </div>
        <div className="rd-act-meta">
          {moneyLine} · до {deadlineStr}
        </div>
        {(badge || skladchina.affectsReputation) && (
          <div className="rd-badges-row">
            {badge && <span className={`rd-badge ${badge.accent ? 'rd-warn' : 'rd-neutral'}`}>{badge.text}</span>}
            {skladchina.affectsReputation && (
              <span className="rd-badge rd-rep" title="Важный сбор: влияет на репутацию участников">⚠️ Важный сбор</span>
            )}
          </div>
        )}
      </div>
    </button>
  );
};
