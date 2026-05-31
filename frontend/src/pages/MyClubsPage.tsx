import { FC, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Modal, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useMyClubsQuery } from '../queries/clubs';
import {
  useMyApplicationsQuery,
  useMyAwaitingPaymentQuery,
  useMyPendingApplicationsQuery,
  useOrganizerAwaitingPaymentQuery,
  useResendInvoiceMutation,
} from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { Toast } from '../components/Toast';
import { CreateClubModal } from '../components/CreateClubModal';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { ApplicationReviewModal } from '../components/applications/ApplicationReviewModal';
import { formatPeerSignal } from '../features/applications-inbox/lib/peer-signal-format';
import { getClub } from '../api/clubs';
import { ApiError } from '../api/apiClient';
import type {
  AwaitingPaymentApplicationDto,
  ClubDetailDto,
  MembershipDto,
  OrganizerAwaitingPaymentApplicantDto,
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

const AWAITING_PAYMENT_LABEL = 'Ожидает оплаты';

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

/**
 * Russian relative date — "сегодня" / "вчера" / "N дней назад" / absolute date.
 * Used on the «Ожидают оплаты» card to soft-cue urgency without a hard deadline.
 */
function formatRelativeApprovedAt(iso: string): string {
  const approvedAt = new Date(iso);
  const now = new Date();
  const ms = now.getTime() - approvedAt.getTime();
  const days = Math.floor(ms / (1000 * 60 * 60 * 24));
  if (days <= 0) return 'одобрено сегодня';
  if (days === 1) return 'одобрено вчера';
  if (days < 7) return `одобрено ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `одобрено ${formatApplicationDate(iso)}`;
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
  awaitingPayment: boolean;
  onClick: () => void;
}

const AppCard: FC<AppCardProps> = ({ application, club, awaitingPayment, onClick }) => {
  const name = club?.name ?? `Клуб ${application.clubId.slice(0, 8)}…`;
  const initials = club ? getInitials(club.name) : '·';
  const status = application.status;
  // approved + still in awaiting-payment list = invoice unpaid. Surface the
  // lifecycle state ("Ожидает оплаты") rather than the misleading "Одобрено".
  const statusLabel = awaitingPayment ? AWAITING_PAYMENT_LABEL : (STATUS_LABELS[status] ?? status);
  const statusClass = awaitingPayment ? 'awaiting-payment' : status;
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
      <span className={`status ${statusClass}`}>{statusLabel}</span>
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

interface AwaitingPaymentCardProps {
  item: AwaitingPaymentApplicationDto;
}

/**
 * Applicant-side card for an approved application without an active membership.
 * Tapping «Открыть счёт в Telegram» re-triggers the Stars invoice via DM.
 * Backend rate-limits 1 call per 60s per application → 429 maps to a specific
 * "wait a minute" message; other errors surface a generic Russian fallback.
 */
const AwaitingPaymentCard: FC<AwaitingPaymentCardProps> = ({ item }) => {
  const resendMutation = useResendInvoiceMutation();
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'error'; text: string } | null>(
    null,
  );

  const initials = getInitials(item.club.name) || '·';

  const handleResend = () => {
    setFeedback(null);
    resendMutation.mutate(item.applicationId, {
      onSuccess: () => {
        setFeedback({
          kind: 'success',
          text: 'Счёт отправлен. Откройте чат с ботом @clubs_admin_bot',
        });
      },
      onError: (e) => {
        if (e instanceof ApiError && e.status === 429) {
          setFeedback({
            kind: 'error',
            text: 'Счёт уже отправлен. Подождите минуту.',
          });
          return;
        }
        const message = e instanceof Error && e.message ? e.message : 'Не удалось отправить счёт. Попробуйте позже.';
        setFeedback({ kind: 'error', text: message });
      },
    });
  };

  // Mirrors `.club-card` layout (52px avatar, 14px padding, body column with
  // name → meta → action). The pay-button lives INSIDE `.body` as a full-width
  // row so the card has the same flex shape as neighbouring active-club cards
  // — no third column, no off-axis spacing.
  return (
    <div className="awaiting-payment-card-wrap">
      <div className="club-card awaiting-payment-card">
        <span className="avt">
          {item.club.avatarUrl ? <img src={item.club.avatarUrl} alt="" /> : initials}
        </span>
        <div className="body">
          <div className="top">
            <span className="name">{item.club.name}</span>
          </div>
          <div className="meta">
            <span>{formatRelativeApprovedAt(item.approvedAt)}</span>
            <span className="price">Цена: {item.subscriptionPrice}⭐</span>
          </div>
          <button
            type="button"
            className="awaiting-pay-btn"
            onClick={handleResend}
            disabled={resendMutation.isPending}
          >
            {resendMutation.isPending ? 'Отправляем…' : `Оплатить ${item.subscriptionPrice}⭐`}
          </button>
        </div>
      </div>
      {feedback && (
        <span className={`awaiting-pay-toast${feedback.kind === 'error' ? ' error' : ''}`}>
          {feedback.text}
        </span>
      )}
    </div>
  );
};

interface OrganizerAwaitingPaymentRowProps {
  item: OrganizerAwaitingPaymentApplicantDto;
}

/**
 * Cross-club organizer-side row: shows an applicant who's been approved for
 * one of the caller's clubs but hasn't paid the Stars invoice yet. Non-
 * interactive (no modal opens from here) — purely informational so the
 * organizer doesn't have to enter each club to see who's pending payment.
 *
 * Kept lightweight (44px avatar, 12px padding) — sits in its own section
 * without club-card neighbours, so applicant-card visual weight would be
 * out of place here.
 */
const OrganizerAwaitingPaymentRow: FC<OrganizerAwaitingPaymentRowProps> = ({ item }) => {
  const fullName = `${item.firstName}${item.lastName ? ` ${item.lastName}` : ''}`;
  const initials = getInitials(fullName) || '·';
  const relative = formatRelativeApprovedAt(item.approvedAt);

  return (
    <div className="mc-app organizer-pending-row">
      <span className="avt">
        {item.avatarUrl ? <img src={item.avatarUrl} alt="" /> : initials}
      </span>
      <div className="body">
        <span className="name">
          {fullName}
          {item.telegramUsername && (
            <span className="handle"> · @{item.telegramUsername}</span>
          )}
        </span>
        <span className="meta">{item.club.name} · {relative}</span>
      </div>
      <span className="status awaiting-payment">{AWAITING_PAYMENT_LABEL}</span>
    </div>
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
  const awaitingPaymentQuery = useMyAwaitingPaymentQuery();
  const organizerAwaitingPaymentQuery = useOrganizerAwaitingPaymentQuery();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [reviewing, setReviewing] = useState<PendingApplicationDto | null>(null);

  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];
  const pendingInbox = pendingInboxQuery.data ?? [];
  const awaitingPayment = awaitingPaymentQuery.data ?? [];
  const organizerAwaitingPayment = organizerAwaitingPaymentQuery.data ?? [];
  const awaitingPaymentIds = useMemo(
    () => new Set(awaitingPayment.map((item) => item.applicationId)),
    [awaitingPayment],
  );

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
    pendingInbox.length === 0 &&
    awaitingPayment.length === 0 &&
    organizerAwaitingPayment.length === 0;

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

      {/*
        Section order (top → bottom): see docs/modules/my-clubs-unified.md.
        1. «Ожидают оплаты» — applicant-side urgent CTA (re-send Stars invoice).
        2. «Заявки на рассмотрении» — organizer-side cross-club inbox.
        3. «Ожидают оплаты от заявителей» — organizer-side cross-club pending payments.
        4. «Активные клубы» — current memberships.
        5. «Заявки» — applicant-side pending/rejected applications.
      */}

      {/* Awaiting payment — applicant must (re)open the Stars invoice */}
      {!loading && awaitingPayment.length > 0 && (
        <>
          <div className="mc-section-label">
            Ожидают оплаты <span className="count">· {awaitingPayment.length}</span>
          </div>
          <div className="mc-list">
            {awaitingPayment.map((item) => (
              <AwaitingPaymentCard key={item.applicationId} item={item} />
            ))}
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

      {/* Organizer cross-club awaiting-payment — applicants approved but unpaid */}
      {!loading && organizerAwaitingPayment.length > 0 && (
        <>
          <div className="mc-section-label">
            Ожидают оплаты от заявителей <span className="count">· {organizerAwaitingPayment.length}</span>
          </div>
          <div className="mc-list">
            {organizerAwaitingPayment.map((item) => (
              <OrganizerAwaitingPaymentRow key={item.applicationId} item={item} />
            ))}
          </div>
        </>
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

      {/* Applications — applicant-side pending/rejected */}
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
                awaitingPayment={awaitingPaymentIds.has(app.id)}
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
