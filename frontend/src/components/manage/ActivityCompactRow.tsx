import { FC } from 'react';
import type { ActivityItemDto } from '../../api/activities';

interface ActivityCompactRowProps {
  activity: ActivityItemDto;
  onClick: () => void;
}

const DATE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
});

function formatShortDate(iso: string): string {
  return DATE_FMT.format(new Date(iso));
}

function activityDate(activity: ActivityItemDto): string {
  const iso =
    activity.type === 'event' ? activity.eventDatetime : activity.deadline;
  return formatShortDate(iso);
}

export const ActivityCompactRow: FC<ActivityCompactRowProps> = ({
  activity,
  onClick,
}) => (
  <button
    type="button"
    onClick={onClick}
    aria-label={`${activity.title}. Завершено`}
    className="rd-rep-row"
  >
    <div className="rd-info">
      <div className="rd-ttl">{activity.title}</div>
    </div>
    <div className="rd-score">
      <span className="rd-cap">{activityDate(activity)}</span>
    </div>
  </button>
);
