import { FC } from 'react';
import type {
  ActivityItemDto,
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';

interface ActivityCardProps {
  activity: ActivityItemDto;
  onClick: () => void;
}

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
    <div className="rd-ft-body">
      <div className="rd-ft-title">{event.title}</div>
      <div className="rd-ft-sub">
        {formatDatetime(event.eventDatetime)} · {event.locationText}
      </div>
      {event.descriptionPreview !== null && (
        <div className="rd-ft-sub">{event.descriptionPreview}</div>
      )}
    </div>
    <div className="rd-ft-stat">
      <div className="rd-ft-stat-num">{event.goingCount}/{event.participantLimit}</div>
      <div className="rd-ft-stat-cap">идёт</div>
    </div>
  </>
);

const SkladchinaCardBody: FC<{ skladchina: SkladchinaActivityDto }> = ({ skladchina }) => {
  const hasGoal = skladchina.totalGoalKopecks !== null && skladchina.totalGoalKopecks > 0;
  const pct = hasGoal
    ? progressPercent(skladchina.collectedKopecks, skladchina.totalGoalKopecks!)
    : 0;

  return (
    <>
      <div className="rd-ft-body">
        <div className="rd-ft-title">{skladchina.title}</div>
        <div className="rd-ft-sub">
          {hasGoal
            ? `${formatRub(skladchina.collectedKopecks)} / ${formatRub(skladchina.totalGoalKopecks!)}`
            : `${formatRub(skladchina.collectedKopecks)} собрано`}
          {skladchina.affectsReputation && ' · ⚠️ Репутация'}
        </div>
      </div>
      <div className="rd-ft-stat">
        {hasGoal ? (
          <>
            <div className="rd-ft-stat-num">{pct}%</div>
            <div className="rd-ft-stat-cap">собрано</div>
          </>
        ) : (
          <>
            <div className="rd-ft-stat-num">{skladchina.paidCount}</div>
            <div className="rd-ft-stat-cap">оплат</div>
          </>
        )}
      </div>
    </>
  );
};

export const ActivityCard: FC<ActivityCardProps> = ({ activity, onClick }) => {
  const ariaLabel = activity.isCompleted ? `${activity.title}. Завершено` : activity.title;

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={ariaLabel}
      className="rd-feature rd-glass"
      style={activity.isCompleted ? { opacity: 0.6 } : undefined}
    >
      {activity.type === 'event' ? (
        <EventCardBody event={activity} />
      ) : (
        <SkladchinaCardBody skladchina={activity} />
      )}
    </button>
  );
};
