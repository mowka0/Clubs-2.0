import { FC } from 'react';
import type { MyEventListItemDto } from '../../types/api';

interface EventCardProps {
  event: MyEventListItemDto;
  onClick: () => void;
}

interface Badge {
  text: string;
  accent: boolean;
}

const WEEKDAY_FORMATTER = new Intl.DateTimeFormat('ru-RU', { weekday: 'short' });

const TIME_FORMATTER = new Intl.DateTimeFormat('ru-RU', {
  hour: '2-digit',
  minute: '2-digit',
});

function formatDateBadge(iso: string): string {
  const d = new Date(iso);
  return `${WEEKDAY_FORMATTER.format(d).toUpperCase()} · ${TIME_FORMATTER.format(d)}`;
}

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

function pickBadge(event: MyEventListItemDto): Badge | null {
  if (event.actionRequired) {
    if (event.status === 'stage_2') return { text: 'Подтверди участие', accent: true };
    return { text: 'Проголосуй', accent: true };
  }
  switch (event.myParticipationStatus) {
    case 'confirmed':  return { text: 'Подтверждён',   accent: false };
    case 'waitlisted': return { text: 'Лист ожидания', accent: false };
    case 'declined':   return { text: 'Отказался',     accent: false };
  }
  switch (event.myVote) {
    case 'going':     return { text: 'Иду',      accent: false };
    case 'maybe':     return { text: 'Возможно', accent: false };
    case 'not_going': return { text: 'Не иду',   accent: false };
  }
  return null;
}

/** Current participant count: confirmed once voting closed (stage_2), else "going". */
function currentCount(event: MyEventListItemDto): number {
  return event.status === 'stage_2' ? event.confirmedCount : event.goingCount;
}

export const EventCard: FC<EventCardProps> = ({ event, onClick }) => {
  const badge = pickBadge(event);
  const clubInitials = getInitials(event.clubName);
  const meta = [event.locationText, `${currentCount(event)} идут`].filter(Boolean).join(' · ');

  return (
    <button type="button" className="rd-activity-card" onClick={onClick}>
      <div
        className="rd-act-cover"
        style={event.clubAvatarUrl ? { backgroundImage: `url(${event.clubAvatarUrl})`, backgroundSize: 'cover', backgroundPosition: 'center' } : undefined}
      >
        <span className="rd-type-badge">СОБЫТИЕ</span>
        <span className="rd-date-badge">{formatDateBadge(event.eventDatetime)}</span>
      </div>
      <div className="rd-act-body">
        <div className="rd-act-club-row">
          <span className="rd-club-avt">
            {event.clubAvatarUrl ? <img src={event.clubAvatarUrl} alt="" /> : clubInitials}
          </span>
          <span>{event.clubName}</span>
        </div>
        <div className="rd-act-ttl">{event.title}</div>
        <div className="rd-act-meta">{meta}</div>
        {badge?.accent && (
          <div className="rd-badges-row">
            <span className="rd-badge rd-warn">{badge.text}</span>
          </div>
        )}
      </div>
    </button>
  );
};
