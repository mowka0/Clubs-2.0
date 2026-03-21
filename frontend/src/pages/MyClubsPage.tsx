import { FC, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { List, Section, Cell, Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubsStore } from '../store/useClubsStore';
import { useApplicationsStore } from '../store/useApplicationsStore';
import { getClub } from '../api/clubs';
import type { ClubDetailDto } from '../types/api';

const STATUS_LABELS: Record<string, string> = {
  pending: 'На рассмотрении',
  approved: 'Одобрено',
  rejected: 'Отклонено',
  auto_rejected: 'Отклонено автоматически',
};

const STATUS_COLOR: Record<string, string> = {
  pending: 'var(--tgui--hint_color)',
  approved: '#34c759',
  rejected: 'var(--tgui--destructive_text_color)',
  auto_rejected: 'var(--tgui--destructive_text_color)',
};

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });
}

export const MyClubsPage: FC = () => {
  const navigate = useNavigate();
  const { myClubs, loading: clubsLoading, fetchMyClubs } = useClubsStore();
  const { applications, loading: appsLoading, fetchMyApplications } = useApplicationsStore();
  const [clubDetails, setClubDetails] = useState<Record<string, ClubDetailDto>>({});

  useEffect(() => {
    fetchMyClubs();
    fetchMyApplications();
  }, [fetchMyClubs, fetchMyApplications]);

  // Fetch club details for memberships
  useEffect(() => {
    const membershipClubIds = myClubs.map((m) => m.clubId).filter((id) => !clubDetails[id]);
    const appClubIds = applications.map((a) => a.clubId).filter((id) => !clubDetails[id]);
    const allIds = [...new Set([...membershipClubIds, ...appClubIds])];
    if (allIds.length === 0) return;

    allIds.forEach((id) => {
      getClub(id)
        .then((club) => setClubDetails((prev) => ({ ...prev, [id]: club })))
        .catch(() => {});
    });
  }, [myClubs, applications]); // eslint-disable-line react-hooks/exhaustive-deps

  const loading = clubsLoading || appsLoading;

  return (
    <List>
      <Section header="Мои клубы">
        {loading && <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}><Spinner size="m" /></div>}
        {!loading && myClubs.length === 0 && (
          <Placeholder description="Вы пока не состоите ни в одном клубе" />
        )}
        {myClubs.map((m) => {
          const club = clubDetails[m.clubId];
          return (
            <Cell
              key={m.id}
              onClick={() => navigate(`/clubs/${m.clubId}`)}
              subtitle={m.role === 'organizer' ? 'Организатор' : 'Участник'}
            >
              {club?.name ?? `Клуб ${m.clubId.slice(0, 8)}…`}
            </Cell>
          );
        })}
      </Section>

      <Section header="Мои заявки">
        {!loading && applications.length === 0 && (
          <Placeholder description="Нет поданных заявок" />
        )}
        {applications.map((app) => {
          const club = clubDetails[app.clubId];
          return (
            <Cell
              key={app.id}
              subtitle={app.createdAt ? formatDate(app.createdAt) : ''}
              after={
                <span style={{ fontSize: 12, color: STATUS_COLOR[app.status] ?? 'inherit' }}>
                  {STATUS_LABELS[app.status] ?? app.status}
                </span>
              }
            >
              {club?.name ?? `Клуб ${app.clubId.slice(0, 8)}…`}
            </Cell>
          );
        })}
      </Section>
    </List>
  );
};
