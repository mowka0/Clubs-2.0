import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { Section, Cell, Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubEventsQuery } from '../../queries/events';

interface ClubEventsTabProps {
  clubId: string;
}

const UPCOMING_STATUSES = new Set(['upcoming', 'stage_1', 'stage_2']);
const PAST_EVENTS_LIMIT = 5;

function formatEventDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleString('ru-RU', {
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
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
      <Section>
        <Placeholder
          header="Ошибка"
          description={eventsQuery.error.message}
        />
      </Section>
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

  return (
    <>
      {upcomingEvents.length > 0 ? (
        <Section header="Ближайшие">
          {upcomingEvents.map((event) => (
            <Cell
              key={event.id}
              subtitle={`${formatEventDate(event.eventDatetime)} · ${event.locationText}`}
              after={
                <span style={{ color: 'var(--tgui--hint_color)', fontSize: 13, whiteSpace: 'nowrap' }}>
                  {event.goingCount}/{event.participantLimit}
                </span>
              }
              onClick={() => { haptic.impact('light'); navigate(`/events/${event.id}`); }}
              multiline
            >
              {event.title}
            </Cell>
          ))}
        </Section>
      ) : (
        <Section>
          <Placeholder description="Нет предстоящих событий" />
        </Section>
      )}

      {pastEvents.length > 0 && (
        <Section header="Прошедшие">
          {pastEvents.map((event) => (
            <Cell
              key={event.id}
              subtitle={formatEventDate(event.eventDatetime)}
              after={
                <span style={{ color: 'var(--tgui--hint_color)', fontSize: 13, whiteSpace: 'nowrap' }}>
                  {event.goingCount}/{event.participantLimit}
                </span>
              }
              onClick={() => { haptic.impact('light'); navigate(`/events/${event.id}`); }}
              multiline
            >
              {event.title}
            </Cell>
          ))}
        </Section>
      )}
    </>
  );
};
