import { FC } from 'react';
import type {
  ActivityItemDto,
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';
import { ActivityThumb } from './ActivityThumb';

interface ActivityCardProps {
  activity: ActivityItemDto;
  onClick: () => void;
}

const TYPE_EMOJI = { event: '🗓', skladchina: '💰' } as const;

const DATETIME_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
});

function formatDatetime(iso: string): string {
  return DATETIME_FMT.format(new Date(iso));
}

function formatRub(kopecks: number): string {
  const rub = Math.floor(kopecks / 100);
  return `${rub.toLocaleString('ru-RU')} ₽`;
}

function progressPercent(collected: number, goal: number): number {
  return Math.min(100, Math.max(0, Math.round((collected / goal) * 100)));
}

const EventCardBody: FC<{ event: EventActivityDto }> = ({ event }) => (
  <>
    <span className="title">{event.title}</span>
    <span className="sub">
      {formatDatetime(event.eventDatetime)} · {event.locationText}
    </span>
    {event.descriptionPreview !== null && (
      <span className="sub dim">{event.descriptionPreview}</span>
    )}
    <div className="footer-row">
      <span className="badge">
        {event.goingCount}/{event.participantLimit}
      </span>
    </div>
  </>
);

const SkladchinaCardBody: FC<{ skladchina: SkladchinaActivityDto }> = ({
  skladchina,
}) => {
  const hasGoal =
    skladchina.totalGoalKopecks !== null && skladchina.totalGoalKopecks > 0;
  const pct = hasGoal
    ? progressPercent(skladchina.collectedKopecks, skladchina.totalGoalKopecks!)
    : 0;

  return (
    <>
      <span className="title">{skladchina.title}</span>
      <span className="sub">
        {hasGoal ? (
          <>
            {formatRub(skladchina.collectedKopecks)} /{' '}
            {formatRub(skladchina.totalGoalKopecks!)}
          </>
        ) : (
          <>{formatRub(skladchina.collectedKopecks)} собрано</>
        )}
        {skladchina.affectsReputation && (
          <span className="reputation" title="Влияет на репутацию">
            ⚠️ Репутация
          </span>
        )}
      </span>
      {hasGoal && (
        <div className="progress-bar" aria-hidden="true">
          <div className="fill" style={{ width: `${pct}%` }} />
        </div>
      )}
      <div className="footer-row">
        <span className="badge">
          {skladchina.paidCount}/{skladchina.participantCount}
        </span>
      </div>
    </>
  );
};

export const ActivityCard: FC<ActivityCardProps> = ({ activity, onClick }) => {
  const className = activity.isCompleted
    ? 'activity-card completed'
    : 'activity-card';
  const ariaLabel = activity.isCompleted
    ? `${activity.title}. Завершено`
    : activity.title;

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={ariaLabel}
      className={className}
    >
      <span className="type-badge" aria-hidden="true">
        {TYPE_EMOJI[activity.type]}
      </span>
      <ActivityThumb type={activity.type} photoUrl={activity.photoUrl} />
      <div className="content">
        {activity.isCompleted && <span className="done-badge">Завершено</span>}
        {activity.type === 'event' ? (
          <EventCardBody event={activity} />
        ) : (
          <SkladchinaCardBody skladchina={activity} />
        )}
      </div>
    </button>
  );
};
