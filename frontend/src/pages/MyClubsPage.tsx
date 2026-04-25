import { FC, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { List, Section, Cell, Button, Modal, Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useMyClubsQuery } from '../queries/clubs';
import { useMyApplicationsQuery } from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { Toast } from '../components/Toast';
import { CreateClubModal } from '../components/CreateClubModal';
import { getClub } from '../api/clubs';
import type { ClubDetailDto } from '../types/api';

interface MyClubsLocationState {
  toast?: string;
}

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
  const location = useLocation();
  const haptic = useHaptic();

  const myClubsQuery = useMyClubsQuery();
  const applicationsQuery = useMyApplicationsQuery();
  const [showCreateModal, setShowCreateModal] = useState(false);

  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];

  // Read one-shot toast from navigation state (e.g. "Клуб X удалён" after delete).
  // Clear it after first render so a refresh/back doesn't show the same toast twice.
  const navState = location.state as MyClubsLocationState | null;
  const [toastMessage, setToastMessage] = useState<string | null>(navState?.toast ?? null);
  useEffect(() => {
    if (navState?.toast) {
      window.history.replaceState(null, '', location.pathname);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Dedup club ids from both memberships and applications so each club is fetched once
  // and shares cache with ClubPage / OrganizerClubManage.
  const clubIds = useMemo(() => {
    const ids = new Set<string>();
    myClubs.forEach((m) => ids.add(m.clubId));
    applications.forEach((a) => ids.add(a.clubId));
    return Array.from(ids);
  }, [myClubs, applications]);

  const clubDetailQueries = useQueries({
    queries: clubIds.map((id) => ({
      queryKey: queryKeys.clubs.detail(id),
      queryFn: () => getClub(id),
    })),
  });

  const clubDetails: Record<string, ClubDetailDto> = {};
  clubIds.forEach((id, idx) => {
    const q = clubDetailQueries[idx];
    if (q?.data) clubDetails[id] = q.data;
  });

  const loading = myClubsQuery.isPending || applicationsQuery.isPending;

  const handleCreated = (id: string) => {
    setShowCreateModal(false);
    // Cache for clubs.my() is invalidated by useCreateClubMutation onSuccess.
    // Navigate to manage page which fetches the new club fresh.
    navigate(`/clubs/${id}/manage`);
  };

  // Always land on the unified ClubPage regardless of role. Organizers drill
  // into management via the "Управление" tab inside the unified page — same
  // entry-point as Discovery, consistent UX. The `role` parameter is no longer
  // read here (kept on the call sites for badge display).
  const handleClubClick = (clubId: string) => {
    haptic.impact('light');
    navigate(`/clubs/${clubId}`);
  };

  return (
    <List>
      <Section>
        <div style={{ padding: 16 }}>
          <Button
            size="l"
            stretched
            onClick={() => { haptic.impact('light'); setShowCreateModal(true); }}
          >
            + Создать клуб
          </Button>
        </div>
      </Section>

      {/* Single combined empty state when there's nothing to show — avoids two adjacent
          placeholders («нет клубов» + «нет заявок») feeling like clutter for new users. */}
      {!loading && myClubs.length === 0 && applications.length === 0 && (
        <Placeholder description="Вы пока не состоите ни в одном клубе. Найдите интересный в Поиске или создайте свой выше." />
      )}

      {(loading || myClubs.length > 0) && (
        <Section header="Мои клубы">
          {loading && <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}><Spinner size="m" /></div>}
          {myClubs.map((m) => {
            const club = clubDetails[m.clubId];
            return (
              <Cell
                key={m.id}
                onClick={() => handleClubClick(m.clubId)}
                subtitle={m.role === 'organizer' ? 'Организатор' : 'Участник'}
              >
                {club?.name ?? `Клуб ${m.clubId.slice(0, 8)}…`}
              </Cell>
            );
          })}
        </Section>
      )}

      {applications.length > 0 && (
        <Section header="Мои заявки">
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
      )}

      {showCreateModal && (
        <Modal open onOpenChange={(open) => !open && setShowCreateModal(false)}>
          <CreateClubModal onClose={() => setShowCreateModal(false)} onCreated={handleCreated} />
        </Modal>
      )}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </List>
  );
};
