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

// У сбора «По желанию» срока может не быть — тогда строка показывает дату создания.
function activityDate(activity: ActivityItemDto): string {
  const iso =
    activity.type === 'event' ? activity.eventDatetime : activity.deadline ?? activity.createdAt;
  return formatShortDate(iso);
}

// Две иконки на всю историю (PO 2026-09-14): встреча и сбор, без зоопарка по видам сборов.
const TYPE_ICON: Record<ActivityItemDto['type'], string> = { event: '📅', skladchina: '💰' };
const TYPE_LABEL: Record<ActivityItemDto['type'], string> = { event: 'Встреча', skladchina: 'Сбор' };

export const ActivityCompactRow: FC<ActivityCompactRowProps> = ({
  activity,
  onClick,
}) => (
  <button
    type="button"
    onClick={onClick}
    aria-label={`${TYPE_LABEL[activity.type]}: ${activity.title}. Завершено`}
    className="rd-rep-row rd-past-row"
  >
    <span className="rd-ico rd-past-ico" aria-hidden="true">{TYPE_ICON[activity.type]}</span>
    <div className="rd-info">
      <div className="rd-ttl">{activity.title}</div>
    </div>
    <div className="rd-score">
      <span className="rd-cap">{activityDate(activity)}</span>
    </div>
  </button>
);
