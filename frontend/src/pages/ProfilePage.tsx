import { FC, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { List, Section, Cell, Spinner, Placeholder, Avatar, Text } from '@telegram-apps/telegram-ui';
import { useAuthStore } from '../store/useAuthStore';
import { useClubsStore } from '../store/useClubsStore';
import { useApplicationsStore } from '../store/useApplicationsStore';

export const ProfilePage: FC = () => {
  const navigate = useNavigate();
  const { user, login, isLoading: authLoading } = useAuthStore();
  const { myClubs, loading: clubsLoading, fetchMyClubs } = useClubsStore();
  const { applications, fetchMyApplications } = useApplicationsStore();

  useEffect(() => {
    if (!user) login();
    fetchMyClubs();
    fetchMyApplications();
  }, [user, login, fetchMyClubs, fetchMyApplications]);

  if (authLoading || clubsLoading) {
    return <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}><Spinner size="l" /></div>;
  }

  if (!user) {
    return <Placeholder header="Ошибка" description="Не удалось загрузить профиль" />;
  }

  const pendingApps = applications.filter((a) => a.status === 'pending');
  const previewClubs = myClubs.slice(0, 5);

  return (
    <List>
      {/* User header */}
      <Section>
        <div style={{ padding: 20, display: 'flex', gap: 16, alignItems: 'center' }}>
          {user.avatarUrl ? (
            <Avatar src={user.avatarUrl} size={48} />
          ) : (
            <div style={{ width: 64, height: 64, borderRadius: '50%', background: 'var(--tgui--secondary_bg_color)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28 }}>👤</div>
          )}
          <div>
            <Text weight="1" style={{ fontSize: 20, display: 'block' }}>
              {user.firstName}{user.lastName ? ` ${user.lastName}` : ''}
            </Text>
            {user.telegramUsername && (
              <span style={{ color: 'var(--tgui--hint_color)', fontSize: 14 }}>@{user.telegramUsername}</span>
            )}
          </div>
        </div>
      </Section>

      {/* Stats */}
      <Section header="Статистика">
        <Cell after={<span style={{ color: 'var(--tgui--hint_color)' }}>{myClubs.length}</span>}>
          Клубов
        </Cell>
        <Cell after={<span style={{ color: pendingApps.length > 0 ? 'var(--tgui--button_color)' : 'var(--tgui--hint_color)' }}>{pendingApps.length}</span>}>
          Ожидают ответа
        </Cell>
      </Section>

      {/* My clubs preview */}
      {previewClubs.length > 0 && (
        <Section header="Мои клубы">
          {previewClubs.map((m) => (
            <Cell
              key={m.id}
              onClick={() => navigate(`/clubs/${m.clubId}`)}
              subtitle={m.role === 'organizer' ? 'Организатор' : 'Участник'}
            >
              Клуб {m.clubId.slice(0, 8)}…
            </Cell>
          ))}
          {myClubs.length > 5 && (
            <Cell onClick={() => navigate('/my-clubs')} style={{ color: 'var(--tgui--button_color)' }}>
              Показать все ({myClubs.length})
            </Cell>
          )}
        </Section>
      )}

      {/* Pending applications */}
      {pendingApps.length > 0 && (
        <Section header="Активные заявки">
          {pendingApps.map((app) => (
            <Cell
              key={app.id}
              subtitle="На рассмотрении"
              onClick={() => navigate(`/clubs/${app.clubId}`)}
            >
              Клуб {app.clubId.slice(0, 8)}…
            </Cell>
          ))}
        </Section>
      )}

      {myClubs.length === 0 && !clubsLoading && (
        <Placeholder
          description="Вы пока не состоите ни в одном клубе"
        >
          <Cell onClick={() => navigate('/')} style={{ color: 'var(--tgui--button_color)' }}>
            Найти клуб
          </Cell>
        </Placeholder>
      )}
    </List>
  );
};
