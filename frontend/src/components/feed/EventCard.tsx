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

const DATE_FORMATTER = new Intl.DateTimeFormat('ru-RU', {
  weekday: 'short',
  day: 'numeric',
  month: 'long',
});

const TIME_FORMATTER = new Intl.DateTimeFormat('ru-RU', {
  hour: '2-digit',
  minute: '2-digit',
});

function formatDate(iso: string): { date: string; time: string } {
  const d = new Date(iso);
  return { date: DATE_FORMATTER.format(d), time: TIME_FORMATTER.format(d) };
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

/** Fill ratio clamped to [0, 1]; empty when there is no limit (avoids divide-by-zero). */
function fillRatio(event: MyEventListItemDto): number {
  if (event.participantLimit <= 0) return 0;
  return Math.min(1, Math.max(0, currentCount(event) / event.participantLimit));
}

export const EventCard: FC<EventCardProps> = ({ event, onClick }) => {
  const { date, time } = formatDate(event.eventDatetime);
  const badge = pickBadge(event);
  const stats = `${currentCount(event)}/${event.participantLimit}`;
  const ratio = fillRatio(event);
  const clubInitials = getInitials(event.clubName);

  const badgeClass = badge?.accent
    ? 'feed-card-badge accent'
    : 'feed-card-badge';

  return (
    <button type="button" className="feed-card" onClick={onClick}>
      <div className="feed-card-date">
        <span className="date">{date}</span>
        <span className="time">{time}</span>
      </div>
      <div className="feed-card-body">
        <div className="title">{event.title}</div>
        <div className="place">{event.locationText}</div>
        <div className="club">
          <span className="club-avt">
            {event.clubAvatarUrl
              ? <img src={event.clubAvatarUrl} alt="" />
              : clubInitials}
          </span>
          <span className="club-name">{event.clubName}</span>
        </div>
        <div className="footer-row">
          {badge && <span className={badgeClass}>{badge.text}</span>}
          <span className="stats">{stats}</span>
        </div>
        <div className="feed-card-progress" aria-hidden="true">
          <span className="bar" style={{ width: `${ratio * 100}%` }} />
        </div>
      </div>
    </button>
  );
};
