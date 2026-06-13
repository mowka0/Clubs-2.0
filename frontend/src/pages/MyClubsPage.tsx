import { FC, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Modal, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useMyClubsQuery } from '../queries/clubs';
import { useMyReputationQuery } from '../queries/members';
import {
  useCompleteFreeMembershipMutation,
  useMyApplicationsQuery,
  useMyAwaitingPaymentQuery,
  useMyPendingApplicationsQuery,
  useOrganizerAwaitingPaymentQuery,
  useResendInvoiceMutation,
} from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { Toast } from '../components/Toast';
import { CreateClubModal } from '../components/CreateClubModal';
import { ApplicationReviewModal } from '../components/applications/ApplicationReviewModal';
import { formatPeerSignal } from '../features/applications-inbox/lib/peer-signal-format';
import { getClub } from '../api/clubs';
import { ApiError } from '../api/apiClient';
import { reliabilityTier } from '../utils/reputationTier';
import type {
  AwaitingPaymentApplicationDto,
  ClubDetailDto,
  MembershipDto,
  OrganizerAwaitingPaymentApplicantDto,
  PendingApplicationDto,
  UserClubReputationDto,
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
  const meta = [
    isOrganizer ? 'организатор' : 'участник',
    club ? (CATEGORY_LABELS[category] ?? category) : null,
    club ? `${club.memberCount} / ${club.memberLimit}` : null,
  ].filter(Boolean).join(' · ');

  return (
    <button type="button" className="rd-rep-row" onClick={onClick}>
      <span className="rd-ico">
        {club?.avatarUrl ? <img src={club.avatarUrl} alt="" /> : initials}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {name}
          {isOrganizer && <span aria-label="Вы организатор" title="Вы организатор"> 👑</span>}
        </div>
        <div className="rd-met">{meta}</div>
      </div>
    </button>
  );
};

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

interface HistoryClubCardProps {
  club: UserClubReputationDto;
  onClick: () => void;
}

/** A club the user left but still has a reputation track record in ("История"). */
const HistoryClubCard: FC<HistoryClubCardProps> = ({ club, onClick }) => {
  const tier = reliabilityTier(club.trust);
  const meta = [CATEGORY_LABELS[club.category] ?? club.category, 'вы покинули'].filter(Boolean).join(' · ');
  return (
    <button type="button" className="rd-rep-row" onClick={onClick}>
      <span className="rd-ico">
        {club.clubAvatarUrl ? <img src={club.clubAvatarUrl} alt="" /> : getInitials(club.clubName)}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">{club.clubName}</div>
        <div className="rd-met">{meta}</div>
      </div>
      {club.trust !== null && (
        <div className="rd-score">
          <span className={`rd-v rd-${tier}`}>{club.trust}</span>
          <span className="rd-cap">надёжность</span>
        </div>
      )}
    </button>
  );
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
  const isRejected = status === 'rejected' || status === 'auto_rejected';
  const badgeTone = awaitingPayment ? 'rd-warn' : isRejected ? 'rd-decline' : status === 'approved' ? 'rd-going' : 'rd-neutral';
  const showReason = isRejected && Boolean(application.rejectedReason && application.rejectedReason.trim());

  return (
    <button type="button" className="rd-rep-row" onClick={onClick}>
      <span className="rd-ico">{initials}</span>
      <div className="rd-info">
        <div className="rd-ttl">{name}</div>
        {application.createdAt && (
          <div className="rd-met">Подана {formatApplicationDate(application.createdAt)}</div>
        )}
        {showReason && <div className="rd-met">Причина: {application.rejectedReason}</div>}
      </div>
      <div className="rd-score">
        <span className={`rd-badge ${badgeTone}`}>{statusLabel}</span>
      </div>
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
    <button type="button" className="rd-rep-row" onClick={onClick}>
      <span className="rd-ico">
        {applicant.avatarUrl ? <img src={applicant.avatarUrl} alt="" /> : initials}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {fullName}
          {applicant.telegramUsername && (
            <span style={{ color: 'var(--text-faint)', fontWeight: 400 }}> · @{applicant.telegramUsername}</span>
          )}
        </div>
        <div className="rd-met">{club.name} · {formatPeerSignal(peerStats)}</div>
        <div className="rd-met" style={urgent ? { color: 'var(--accent)' } : undefined}>{hoursLabel}</div>
      </div>
    </button>
  );
};

interface AwaitingPaymentCardProps {
  item: AwaitingPaymentApplicationDto;
}

/**
 * Applicant-side card for an approved application without an active membership.
 * The WHOLE card is tappable — same visual shape as `.club-card` so it sits
 * at the same height as active-club cards stacked below in «Активные». Tap
 * re-triggers the Stars invoice via DM. Backend rate-limits 1 call per 60s
 * per application → 429 maps to a specific "wait a minute" message; other
 * errors surface a generic Russian fallback.
 */
const AwaitingPaymentCard: FC<AwaitingPaymentCardProps> = ({ item }) => {
  const resendMutation = useResendInvoiceMutation();
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'error'; text: string } | null>(
    null,
  );

  const initials = getInitials(item.club.name) || '·';

  const handleResend = () => {
    if (resendMutation.isPending) return;
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

  const inlineLabel = resendMutation.isPending
    ? 'Отправляем счёт…'
    : `Цена: ${item.subscriptionPrice}⭐ · Нажмите чтобы оплатить`;

  return (
    <>
      <button
        type="button"
        className="rd-rep-row"
        onClick={handleResend}
        disabled={resendMutation.isPending}
      >
        <span className="rd-ico">
          {item.club.avatarUrl ? <img src={item.club.avatarUrl} alt="" /> : initials}
        </span>
        <div className="rd-info">
          <div className="rd-ttl">{item.club.name}</div>
          <div className="rd-met">{formatRelativeApprovedAt(item.approvedAt)}</div>
          <div className="rd-met" style={{ color: 'var(--accent)' }}>{inlineLabel}</div>
        </div>
      </button>
      {feedback && (
        <div className="rd-cta-hint" style={{ color: feedback.kind === 'error' ? 'var(--danger)' : 'var(--live)', textAlign: 'left' }}>
          {feedback.text}
        </div>
      )}
    </>
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
    <div className="rd-rep-row">
      <span className="rd-ico">
        {item.avatarUrl ? <img src={item.avatarUrl} alt="" /> : initials}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">
          {fullName}
          {item.telegramUsername && (
            <span style={{ color: 'var(--text-faint)', fontWeight: 400 }}> · @{item.telegramUsername}</span>
          )}
        </div>
        <div className="rd-met">{item.club.name} · {relative}</div>
      </div>
      <div className="rd-score">
        <span className="rd-badge rd-warn">{AWAITING_PAYMENT_LABEL}</span>
      </div>
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
  const reputationQuery = useMyReputationQuery();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [reviewing, setReviewing] = useState<PendingApplicationDto | null>(null);

  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];
  const pendingInbox = pendingInboxQuery.data ?? [];
  const awaitingPayment = awaitingPaymentQuery.data ?? [];
  const organizerAwaitingPayment = organizerAwaitingPaymentQuery.data ?? [];
  const historyClubs = reputationQuery.data?.historyClubs ?? [];
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

  /*
   * Silent self-heal for stuck free-club applications.
   *
   * Background: when a free-club application is approved, the backend creates
   * the membership inline (see ApplicationService.approveApplication free branch).
   * If that branch ever failed for legacy reasons — or the approval came from
   * a pre-fix codepath — the user has status=approved but no membership row,
   * so the club doesn't show in «Активные клубы». They wouldn't know to tap
   * «Завершить вступление» on ClubPage.
   *
   * Fix: on page mount, after both queries settle, find such stuck applications
   * (approved + free club + caller not in active membership) and call
   * completeFreeMembership(applicationId) once each. The mutation is idempotent
   * server-side (400 «Already a member» if race lands a real membership first),
   * silent (no toast, no haptic), and refetches myClubs so the КПСС appears.
   *
   * Paid clubs are explicitly excluded — they correctly remain in «Ожидают
   * оплаты» until the Stars invoice is paid.
   */
  const completeFreeMutation = useCompleteFreeMembershipMutation();
  const autoHealedRef = useRef(false);
  const activeMembershipClubIds = useMemo(
    () => new Set(myClubs.map((m) => m.clubId)),
    [myClubs],
  );
  useEffect(() => {
    if (autoHealedRef.current) return;
    if (myClubsQuery.isPending || applicationsQuery.isPending) return;
    // Need club detail to know whether subscriptionPrice === 0.
    const haveAllClubDetails = clubIds.every((id) => Boolean(clubDetails[id]));
    if (clubIds.length > 0 && !haveAllClubDetails) return;

    const stuck = applications.filter((app) => {
      if (app.status !== 'approved') return false;
      if (activeMembershipClubIds.has(app.clubId)) return false;
      const club = clubDetails[app.clubId];
      if (!club) return false;
      return club.subscriptionPrice === 0;
    });
    if (stuck.length === 0) return;

    autoHealedRef.current = true;
    stuck.forEach((app) => {
      completeFreeMutation.mutate({ applicationId: app.id, clubId: app.clubId });
    });
  }, [
    myClubsQuery.isPending,
    applicationsQuery.isPending,
    applications,
    activeMembershipClubIds,
    clubIds,
    clubDetails,
    completeFreeMutation,
  ]);

  // Inbox grouped by addressee (see docs/modules/my-clubs-unified.md):
  //  - «Мои заявки» (outgoing): only LIVE applications — pending (awaiting the
  //    organizer's decision) + approved-awaiting-payment (needs my payment).
  //    Finished-lifecycle apps (rejected / auto_rejected / approved→member) are
  //    excluded — they're history, not actionable. Awaiting-payment apps render
  //    as rich AwaitingPaymentCard, so they're not in the pending AppCard list.
  //  - «Заявки в мои клубы» (organizer inbox): pending review + applicants
  //    who were approved but haven't paid yet.
  const myActiveApps = useMemo(
    () => applications.filter((a) => a.status === 'pending' && !awaitingPaymentIds.has(a.id)),
    [applications, awaitingPaymentIds],
  );
  const myApplicationsCount = awaitingPayment.length + myActiveApps.length;
  const organizerInboxCount = pendingInbox.length + organizerAwaitingPayment.length;

  const loading = myClubsQuery.isPending || applicationsQuery.isPending;
  const empty =
    !loading &&
    myClubs.length === 0 &&
    myApplicationsCount === 0 &&
    organizerInboxCount === 0 &&
    historyClubs.length === 0;

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
    <div className="rd-page">
      {/* Header */}
      <header className="rd-header">
        <div className="rd-info">
          <div className="rd-ft-eyebrow">
            {user?.firstName ? `Привет, ${user.firstName} 👋` : 'В сообществе'}
          </div>
          <div className="rd-page-h">
            Мои клубы{myClubs.length > 0 ? ` · ${myClubs.length}` : ''}
          </div>
          {(myClubs.length > 0 || myApplicationsCount > 0) && (
            <div className="rd-sub" style={{ marginTop: 2, color: 'var(--text-dim)', fontSize: 13 }}>
              {summaryLine(myClubs.length, myApplicationsCount)}
            </div>
          )}
        </div>
        <button type="button" className="rd-city-pill" onClick={openCreate} aria-label="Создать клуб">
          <span aria-hidden="true">+</span> Клуб
        </button>
      </header>

      {/* Loading spinner */}
      {loading && (
        <div className="rd-spinner-row">
          <Spinner size="m" />
        </div>
      )}

      {/* Empty state */}
      {empty && (
        <div className="rd-glass rd-empty">
          <div style={{ color: 'var(--text-faint)', marginBottom: 8 }}>{PEOPLE_ICON}</div>
          <div className="rd-title">Пока пусто</div>
          <div className="rd-sub">
            Найдите подходящий клуб в&nbsp;«Поиске» или создайте свой — будете звать единомышленников сами.
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'center', flexWrap: 'wrap' }}>
            <button type="button" className="rd-ghost-btn" onClick={handleSearchClick}>Открыть поиск</button>
            <button type="button" className="rd-btn-primary" style={{ width: 'auto', padding: '10px 18px' }} onClick={openCreate}>
              + Создать клуб
            </button>
          </div>
        </div>
      )}

      {/*
        Sections grouped by addressee (see docs/modules/my-clubs-unified.md):
        1. «Мои заявки» — applicant-side: awaiting-payment + pending/rejected.
        2. «Заявки в мои клубы» — organizer-side: pending review + applicants awaiting payment.
        3. «Где я состою» — current memberships.
      */}

      {/* 1. My applications (outgoing) — payment CTA + pending/rejected */}
      {!loading && myApplicationsCount > 0 && (
        <>
          <div className="rd-section-sub-h">
            Мои заявки <span className="rd-count">· {myApplicationsCount}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {awaitingPayment.map((item) => (
              <AwaitingPaymentCard key={item.applicationId} item={item} />
            ))}
            {myActiveApps.map((app) => (
              <AppCard
                key={app.id}
                application={app}
                club={clubDetails[app.clubId]}
                awaitingPayment={false}
                onClick={() => handleClubClick(app.clubId)}
              />
            ))}
          </div>
        </>
      )}

      {/* 2. Applications to my clubs (organizer inbox) — review + awaiting payment */}
      {!loading && organizerInboxCount > 0 && (
        <>
          <div className="rd-section-sub-h">
            Заявки в мои клубы <span className="rd-count">· {organizerInboxCount}</span>
          </div>
          <div ref={inboxSectionRef} className="rd-glass rd-rep-panel">
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
            {organizerAwaitingPayment.map((item) => (
              <OrganizerAwaitingPaymentRow key={item.applicationId} item={item} />
            ))}
          </div>
        </>
      )}

      {/* 3. Active clubs */}
      {!loading && myClubs.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            Где я состою <span className="rd-count">· {myClubs.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
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


      {/* 4. История — clubs the user left but still has a reputation track record in */}
      {!loading && historyClubs.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            История <span className="rd-count">· {historyClubs.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {historyClubs.map((c) => (
              <HistoryClubCard key={c.clubId} club={c} onClick={() => handleClubClick(c.clubId)} />
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
