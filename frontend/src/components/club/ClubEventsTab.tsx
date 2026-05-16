import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubEventsQuery } from '../../queries/events';
import type { EventListItemDto } from '../../types/api';

interface ClubEventsTabProps {
  clubId: string;
}

const UPCOMING_STATUSES = new Set(['upcoming', 'stage_1', 'stage_2']);
const PAST_EVENTS_LIMIT = 5;
const ALMOST_FULL_THRESHOLD = 0.8;
const MONTHS_RU_SHORT = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек'];

function formatDay(iso: string): string {
  return String(new Date(iso).getDate()).padStart(2, '0');
}

function formatMonthShort(iso: string): string {
  return MONTHS_RU_SHORT[new Date(iso).getMonth()] ?? '';
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

function isAlmostFull(event: EventListItemDto): boolean {
  return event.participantLimit > 0 && (event.goingCount / event.participantLimit) >= ALMOST_FULL_THRESHOLD;
}

export const ClubEventsTab: FC<ClubEventsTabProps> = ({ clubId }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const eventsQuery = useClubEventsQuery(clubId, { size: '100' });

  if (eventsQuery.isPending) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (eventsQuery.error) {
    return (
      <div style={{ padding: '0 20px' }}>
        <Placeholder header="Ошибка" description={eventsQuery.error.message} />
      </div>
    );
  }

  const events = eventsQuery.data?.content ?? [];

  const upcomingEvents = events
    .filter((e) => UPCOMING_STATUSES.has(e.status))
    .sort((a, b) => new Date(a.eventDatetime).getTime() - new Date(b.eventDatetime).getTime());

  const pastEvents = events
    .filter((e) => e.status === 'completed')
    .sort((a, b) => new Date(b.eventDatetime).getTime() - new Date(a.eventDatetime).getTime())
    .slice(0, PAST_EVENTS_LIMIT);

  const openEvent = (eventId: string) => {
    haptic.impact('light');
    navigate(`/events/${eventId}`);
  };

  return (
    <>
      {upcomingEvents.length > 0 ? (
        <>
          <div className="cp-section-label">Ближайшие</div>
          <div className="cp-events">
            {upcomingEvents.map((event, idx) => (
              <button
                key={event.id}
                type="button"
                className={`cp-event${idx === 0 ? ' featured' : ''}`}
                onClick={() => openEvent(event.id)}
              >
                <div className="date">
                  <span className="day">{formatDay(event.eventDatetime)}</span>
                  <span className="mon">{formatMonthShort(event.eventDatetime)}</span>
                </div>
                <div className="body">
                  <span className="title">{event.title}</span>
                  <div className="meta">
                    <span className="time">{formatTime(event.eventDatetime)}</span>
                    {event.locationText && (
                      <>
                        <span className="sep">·</span>
                        <span>{event.locationText}</span>
                      </>
                    )}
                  </div>
                  <span className={`going${isAlmostFull(event) ? ' almost-full' : ''}`}>
                    {event.goingCount} / {event.participantLimit} идут
                  </span>
                </div>
              </button>
            ))}
          </div>
        </>
      ) : (
        <div style={{ padding: '0 20px' }}>
          <Placeholder description="Нет предстоящих событий" />
        </div>
      )}

      {pastEvents.length > 0 && (
        <>
          <div className="cp-section-label" style={{ paddingTop: 22 }}>Прошедшие</div>
          <div className="cp-events">
            {pastEvents.map((event) => (
              <button
                key={event.id}
                type="button"
                className="cp-event past"
                onClick={() => openEvent(event.id)}
              >
                <div className="date">
                  <span className="day">{formatDay(event.eventDatetime)}</span>
                  <span className="mon">{formatMonthShort(event.eventDatetime)}</span>
                </div>
                <div className="body">
                  <span className="title">{event.title}</span>
                  <div className="meta">
                    <span>{event.goingCount} пришло</span>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </>
      )}
    </>
  );
};
