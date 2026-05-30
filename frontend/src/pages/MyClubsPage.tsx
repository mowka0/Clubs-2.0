import { FC, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Modal, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useMyClubsQuery } from '../queries/clubs';
import {
  useMyApplicationsQuery,
  useMyPendingApplicationsQuery,
} from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { Toast } from '../components/Toast';
import { CreateClubModal } from '../components/CreateClubModal';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { ApplicationReviewModal } from '../components/applications/ApplicationReviewModal';
import { formatPeerSignal } from '../features/applications-inbox/lib/peer-signal-format';
import { getClub } from '../api/clubs';
import type {
  ClubDetailDto,
  MembershipDto,
  PendingApplicationDto,
} from '../types/api';
import type { ApplicationDto } from '../api/membership';

interface MyClubsLocationState {
  toast?: string;
}

const STATUS_LABELS: Record<string, string> = {
  pending: 'На рассмотрении',
  approved: 'Одобрено',
  rejected: 'Отклонено',
  auto_rejected: 'Отклонено',
};

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

function formatApplicationDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** Russian plural form picker: forms = [one, few, many] */
function pluralRu(n: number, forms: [string, string, string]): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return forms[0];
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
  return forms[2];
}

function summaryLine(clubs: number, apps: number): string {
  const parts: string[] = [];
  if (clubs > 0) parts.push(`${clubs} ${pluralRu(clubs, ['клуб', 'клуба', 'клубов'])}`);
  if (apps > 0) parts.push(`${apps} ${pluralRu(apps, ['заявка', 'заявки', 'заявок'])}`);
  return parts.join(' · ');
}

const PEOPLE_ICON = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </svg>
);

interface MyClubCardProps {
  membership: MembershipDto;
  club: ClubDetailDto | undefined;
  isOrganizer: boolean;
  onClick: () => void;
}

const MyClubCard: FC<MyClubCardProps> = ({ membership, club, isOrganizer, onClick }) => {
  const name = club?.name ?? `Клуб ${membership.clubId.slice(0, 8)}…`;
  const category = club?.category ?? 'other';
  const initials = club ? getInitials(club.name) : '·';
  const capacityPct = club && club.memberLimit > 0
    ? Math.min(100, Math.round((club.memberCount / club.memberLimit) * 100))
    : 0;
  const almostFull = capacityPct >= 80;

  const avtClass = `avt${isOrganizer ? ' organizer-ring' : ''}`;

  return (
    <button type="button" className="club-card" onClick={onClick}>
      <span className={avtClass} data-cat={category}>
        {club?.avatarUrl ? <img src={club.avatarUrl} alt="" /> : initials}
      </span>
      <div className="body">
        <div className="top">
          <span className="name">{name}</span>
          {isOrganizer && (
            <span className="role-crown" aria-label="Вы организатор" title="Вы организатор">
              👑
            </span>
          )}
        </div>
        <div className="meta">
          {club && <span className="cat">{CATEGORY_LABELS[category] ?? category}</span>}
        </div>
        {club && (
          <div className="capacity">
            <div className="capacity-bar"><div className="fill" style={{ width: `${capacityPct}%` }} /></div>
            <span className={`capacity-num${almostFull ? ' almost-full' : ''}`}>
              {club.memberCount} / {club.memberLimit}
            </span>
          </div>
        )}
      </div>
    </button>
  );
};

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

interface AppCardProps {
  application: ApplicationDto;
  club: ClubDetailDto | undefined;
  onClick: () => void;
}

const AppCard: FC<AppCardProps> = ({ application, club, onClick }) => {
  const name = club?.name ?? `Клуб ${application.clubId.slice(0, 8)}…`;
  const initials = club ? getInitials(club.name) : '·';
  const status = application.status;
  const statusLabel = STATUS_LABELS[status] ?? status;
  const showReason =
    (status === 'rejected' || status === 'auto_rejected') &&
    Boolean(application.rejectedReason && application.rejectedReason.trim());

  return (
    <button type="button" className="mc-app" onClick={onClick}>
      <span className="avt">{initials}</span>
      <div className="body">
        <span className="name">{name}</span>
        {application.createdAt && (
          <span className="meta">Подана {formatApplicationDate(application.createdAt)}</span>
        )}
        {showReason && (
          <span className="reason">Причина: {application.rejectedReason}</span>
        )}
      </div>
      <span className={`status ${status}`}>{statusLabel}</span>
    </button>
  );
};

interface PendingAppCardProps {
  pending: PendingApplicationDto;
  onClick: () => void;
}

const PendingAppCard: FC<PendingAppCardProps> = ({ pending, onClick }) => {
  const { applicant, peerStats, club, hoursUntilAutoReject } = pending;
  const fullName = `${applicant.firstName}${applicant.lastName ? ` ${applicant.lastName}` : ''}`;
  const initials = getInitials(fullName) || '·';
  const urgent = hoursUntilAutoReject <= 6;
  const hoursLabel =
    hoursUntilAutoReject > 0
      ? `${hoursUntilAutoReject}ч до автоотклонения`
      : 'Время истекло';

  return (
    <button type="button" className="mc-app pending-app-card" onClick={onClick}>
      <span className="avt">
        {applicant.avatarUrl ? <img src={applicant.avatarUrl} alt="" /> : initials}
      </span>
      <div className="body">
        <span className="name">
          {fullName}
          {applicant.telegramUsername && (
            <span className="handle"> · @{applicant.telegramUsername}</span>
          )}
        </span>
        <span className="meta peer">{formatPeerSignal(peerStats)}</span>
        <span className="club-chip">{club.name}</span>
        <span className={`hours-hint${urgent ? ' urgent' : ''}`}>{hoursLabel}</span>
      </div>
    </button>
  );
};

export const MyClubsPage: FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const haptic = useHaptic();
  const { user } = useAuthStore();

  const myClubsQuery = useMyClubsQuery();
  const applicationsQuery = useMyApplicationsQuery();
  const pendingInboxQuery = useMyPendingApplicationsQuery();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [reviewing, setReviewing] = useState<PendingApplicationDto | null>(null);

  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];
  const pendingInbox = pendingInboxQuery.data ?? [];

  const inboxSectionRef = useRef<HTMLDivElement | null>(null);
  // Idempotent scroll: focus=inbox deep-link must scroll exactly once per
  // mount, even with Vite HMR re-running the effect.
  const focusInboxHandledRef = useRef(false);

  const navState = location.state as MyClubsLocationState | null;
  const [toastMessage, setToastMessage] = useState<string | null>(navState?.toast ?? null);
  useEffect(() => {
    if (navState?.toast) {
      window.history.replaceState(null, '', location.pathname);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Deep-link from DM: /my-clubs?focus=inbox. Scroll to the inbox section once
  // its data is loaded and the section is in the DOM; then strip the query so
  // a refresh doesn't re-trigger. If the user has no pending applications,
  // there is no section and we just clear the query.
  useEffect(() => {
    if (focusInboxHandledRef.current) return;
    if (searchParams.get('focus') !== 'inbox') return;
    if (pendingInboxQuery.isPending) return;

    focusInboxHandledRef.current = true;

    if (pendingInbox.length > 0 && inboxSectionRef.current) {
      inboxSectionRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    const next = new URLSearchParams(searchParams);
    next.delete('focus');
    setSearchParams(next, { replace: true });
  }, [pendingInboxQuery.isPending, pendingInbox.length, searchParams, setSearchParams]);

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
  const empty =
    !loading &&
    myClubs.length === 0 &&
    applications.length === 0 &&
    pendingInbox.length === 0;

  const handleCreated = (id: string) => {
    setShowCreateModal(false);
    navigate(`/clubs/${id}/manage`);
  };

  const openCreate = () => {
    haptic.impact('light');
    setShowCreateModal(true);
  };

  const handleClubClick = (clubId: string) => {
    haptic.impact('light');
    navigate(`/clubs/${clubId}`);
  };

  const handleSearchClick = () => {
    haptic.impact('light');
    navigate('/');
  };

  return (
    <div className="brand-page">
      <BrandBackdrop />

      {/* Hero */}
      <header className="mc-hero">
        {user?.firstName && (
          <div className="greeting">Привет, {user.firstName} <span aria-hidden="true">👋</span></div>
        )}
        <div className="mc-hero-row">
          <h1>
            Твои <span className="accent">клубы</span>
          </h1>
          <button type="button" className="mc-create-btn" onClick={openCreate}>
            <span className="plus">+</span>
            Создать
          </button>
        </div>
        {(myClubs.length > 0 || applications.length > 0) && (
          <div className="sub">{summaryLine(myClubs.length, applications.length)}</div>
        )}
      </header>

      {/* Loading spinner */}
      {loading && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
          <Spinner size="m" />
        </div>
      )}

      {/* Empty state */}
      {empty && (
        <div className="mc-empty">
          <div className="ico">{PEOPLE_ICON}</div>
          <div className="title">Пока пусто</div>
          <div className="sub">
            Найдите подходящий клуб в&nbsp;«Поиске» или создайте свой — будете звать единомышленников сами.
          </div>
          <div className="actions">
            <button type="button" className="ghost-btn" onClick={handleSearchClick}>Открыть поиск</button>
            <button type="button" className="mc-create-btn" onClick={openCreate}>
              <span className="plus">+</span>
              Создать клуб
            </button>
          </div>
        </div>
      )}

      {/* Active clubs */}
      {!loading && myClubs.length > 0 && (
        <>
          <div className="mc-section-label">
            Активные <span className="count">· {myClubs.length}</span>
          </div>
          <div className="mc-list">
            {myClubs.map((m) => {
              const club = clubDetails[m.clubId];
              const isOrganizer = m.role === 'organizer' || club?.ownerId === user?.id;
              return (
                <MyClubCard
                  key={m.id}
                  membership={m}
                  club={club}
                  isOrganizer={isOrganizer}
                  onClick={() => handleClubClick(m.clubId)}
                />
              );
            })}
          </div>
        </>
      )}

      {/* Organizer cross-club inbox — pending applications across all owned clubs */}
      {!loading && pendingInbox.length > 0 && (
        <div ref={inboxSectionRef}>
          <div className="mc-section-label">
            Заявки на рассмотрении <span className="count">· {pendingInbox.length}</span>
          </div>
          <div className="mc-list">
            {pendingInbox.map((p) => (
              <PendingAppCard
                key={p.applicationId}
                pending={p}
                onClick={() => {
                  haptic.impact('light');
                  setReviewing(p);
                }}
              />
            ))}
          </div>
        </div>
      )}

      {/* Applications */}
      {!loading && applications.length > 0 && (
        <>
          <div className="mc-section-label">
            Заявки <span className="count">· {applications.length}</span>
          </div>
          <div className="mc-list">
            {applications.map((app) => (
              <AppCard
                key={app.id}
                application={app}
                club={clubDetails[app.clubId]}
                onClick={() => handleClubClick(app.clubId)}
              />
            ))}
          </div>
        </>
      )}

      {showCreateModal && (
        <Modal open onOpenChange={(open) => !open && setShowCreateModal(false)}>
          <CreateClubModal onClose={() => setShowCreateModal(false)} onCreated={handleCreated} />
        </Modal>
      )}

      {reviewing && (
        <ApplicationReviewModal
          application={reviewing}
          open
          onClose={() => setReviewing(null)}
        />
      )}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};
