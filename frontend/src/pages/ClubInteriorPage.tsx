import { FC, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  List,
  Section,
  Cell,
  Spinner,
  Placeholder,
  Avatar,
  Text,
  TabsList,
  Badge,
} from '@telegram-apps/telegram-ui';
import { useAuthStore } from '../store/useAuthStore';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useClubQuery } from '../queries/clubs';
import { useClubMembersQuery, useMemberProfileQuery } from '../queries/members';
import { useClubEventsQuery } from '../queries/events';

type TabId = 'events' | 'members' | 'profile';

const UPCOMING_STATUSES = new Set(['upcoming', 'stage_1', 'stage_2']);

function formatEventDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleString('ru-RU', {
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function getInitials(firstName: string, lastName: string | null): string {
  const first = firstName.charAt(0).toUpperCase();
  const last = lastName ? lastName.charAt(0).toUpperCase() : '';
  return first + last;
}

export const ClubInteriorPage: FC = () => {
  const { id: clubId } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const user = useAuthStore((s) => s.user);

  useBackButton(true);

  const [activeTab, setActiveTab] = useState<TabId>('events');

  const clubQuery = useClubQuery(clubId);
  const eventsQuery = useClubEventsQuery(clubId, { size: '100' });
  const membersQuery = useClubMembersQuery(clubId);
  const memberProfileQuery = useMemberProfileQuery(clubId, user?.id);

  const club = clubQuery.data;
  const events = eventsQuery.data?.content ?? [];
  const members = membersQuery.data ?? [];
  const memberProfile = memberProfileQuery.data;

  const loading = clubQuery.isPending || eventsQuery.isPending || membersQuery.isPending;
  const error = clubQuery.error?.message ?? eventsQuery.error?.message ?? membersQuery.error?.message;

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (error || !club) {
    return (
      <Placeholder
        header="Ошибка"
        description={error ?? 'Не удалось загрузить данные клуба'}
      />
    );
  }

  const upcomingEvents = events
    .filter((e) => UPCOMING_STATUSES.has(e.status))
    .sort((a, b) => new Date(a.eventDatetime).getTime() - new Date(b.eventDatetime).getTime());

  const pastEvents = events
    .filter((e) => e.status === 'completed')
    .sort((a, b) => new Date(b.eventDatetime).getTime() - new Date(a.eventDatetime).getTime())
    .slice(0, 5);

  return (
    <List>
      {/* Club header */}
      <Section>
        <div style={{ padding: '16px 20px 8px' }}>
          <Text weight="1" style={{ fontSize: 22, display: 'block' }}>
            {club.name}
          </Text>
          <span style={{ color: 'var(--tgui--hint_color)', fontSize: 14 }}>
            {club.memberCount} / {club.memberLimit} участников
          </span>
        </div>
      </Section>

      {/* Tabs */}
      <div style={{ padding: '0 16px 8px' }}>
        <TabsList>
          <TabsList.Item
            selected={activeTab === 'events'}
            onClick={() => { haptic.select(); setActiveTab('events'); }}
          >
            События
          </TabsList.Item>
          <TabsList.Item
            selected={activeTab === 'members'}
            onClick={() => { haptic.select(); setActiveTab('members'); }}
          >
            Участники
          </TabsList.Item>
          <TabsList.Item
            selected={activeTab === 'profile'}
            onClick={() => { haptic.select(); setActiveTab('profile'); }}
          >
            Мой профиль
          </TabsList.Item>
        </TabsList>
      </div>

      {/* Tab: Events */}
      {activeTab === 'events' && (
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
      )}

      {/* Tab: Members */}
      {activeTab === 'members' && (
        <Section header={`Участники (${members.length})`}>
          {members.length === 0 && (
            <Placeholder description="Список участников пуст" />
          )}
          {members.map((member) => (
            <Cell
              key={member.userId}
              before={
                member.avatarUrl ? (
                  <Avatar src={member.avatarUrl} size={40} />
                ) : (
                  <Avatar
                    size={40}
                    acronym={getInitials(member.firstName, member.lastName)}
                  />
                )
              }
              subtitle={`Надёжность: ${member.reliabilityIndex}`}
              after={
                member.role === 'organizer' ? (
                  <Badge type="number" mode="primary">
                    Организатор
                  </Badge>
                ) : undefined
              }
              multiline
            >
              {member.firstName}{member.lastName ? ` ${member.lastName}` : ''}
            </Cell>
          ))}
        </Section>
      )}

      {/* Tab: My Profile */}
      {activeTab === 'profile' && (
        <>
          {memberProfile ? (
            <>
              {/* Profile header */}
              <Section>
                <div style={{ padding: 20, display: 'flex', gap: 16, alignItems: 'center' }}>
                  {memberProfile.avatarUrl ? (
                    <Avatar src={memberProfile.avatarUrl} size={48} />
                  ) : (
                    <Avatar
                      size={48}
                      acronym={getInitials(
                        memberProfile.firstName,
                        memberProfile.username ?? memberProfile.firstName,
                      )}
                    />
                  )}
                  <div>
                    <Text weight="1" style={{ fontSize: 20, display: 'block' }}>
                      {memberProfile.firstName}
                    </Text>
                    {memberProfile.username && (
                      <span style={{ color: 'var(--tgui--hint_color)', fontSize: 14 }}>
                        @{memberProfile.username}
                      </span>
                    )}
                  </div>
                </div>
              </Section>

              {/* Reputation */}
              <Section header="Репутация">
                <Cell
                  after={
                    <span style={{ color: 'var(--tgui--hint_color)' }}>
                      {memberProfile.reliabilityIndex}
                    </span>
                  }
                >
                  Индекс надёжности
                </Cell>
                <Cell
                  after={
                    <span style={{ color: 'var(--tgui--hint_color)' }}>
                      {memberProfile.promiseFulfillmentPct}%
                    </span>
                  }
                >
                  Выполнение обещаний
                </Cell>
                <Cell
                  after={
                    <span style={{ color: 'var(--tgui--hint_color)' }}>
                      {memberProfile.totalConfirmations}
                    </span>
                  }
                >
                  Подтверждений
                </Cell>
              </Section>
            </>
          ) : (
            <Section>
              <Placeholder
                header="Профиль недоступен"
                description="Не удалось загрузить ваш профиль в этом клубе"
              />
            </Section>
          )}
        </>
      )}
    </List>
  );
};
