import { FC, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useQueries } from '@tanstack/react-query';
import { Modal, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useLeaveClubMutation, useMyClubsQuery } from '../queries/clubs';
import { useMyReputationQuery, useOrganizerAwaitingDuesQuery } from '../queries/members';
import {
  useCancelApplicationMutation,
  useCompleteFreeMembershipMutation,
  useMyApplicationsQuery,
  useMyPendingApplicationsQuery,
} from '../queries/applications';
import { queryKeys } from '../queries/queryKeys';
import { Toast } from '../components/Toast';
import { CreateClubModal } from '../components/CreateClubModal';
import { ApplicationReviewModal } from '../components/applications/ApplicationReviewModal';
import { MemberProfileModal } from '../components/club/MemberProfileModal';
import { formatPeerSignal } from '../features/applications-inbox/lib/peer-signal-format';
import { LevelPill } from '../components/reputation/LevelPill';
import { getClub } from '../api/clubs';
import { reliabilityTier } from '../utils/reputationTier';
import type {
  ClubDetailDto,
  MemberListItemDto,
  MembershipDto,
  OrganizerDuesMemberDto,
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
  cancelled: 'Отменено',
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
  onClick: () => void;
  /** Withdraw the application (called after the inline «точно?» confirm). */
  onCancel: () => void;
  /** This card's withdraw request is in flight. */
  cancelling: boolean;
}

const AppCard: FC<AppCardProps> = ({ application, club, onClick, onCancel, cancelling }) => {
  // Lightweight inline confirm (no modal): the «×» flips the row into a «Отменить заявку?» two-button
  // state. Simpler than a popup and consistent with the app's other destructive two-tap confirms.
  const [confirming, setConfirming] = useState(false);
  const name = club?.name ?? `Клуб ${application.clubId.slice(0, 8)}…`;
  const initials = club ? getInitials(club.name) : '·';

  if (confirming) {
    return (
      <div className="rd-rep-row rd-app-confirm">
        <div className="rd-info">
          <div className="rd-ttl">Отменить заявку?</div>
          <div className="rd-met">«{name}» — можно будет подать заново.</div>
        </div>
        <div className="rd-app-confirm-acts">
          <button type="button" className="rd-app-confirm-no" disabled={cancelling} onClick={() => setConfirming(false)}>
            Нет
          </button>
          <button type="button" className="rd-app-confirm-yes" disabled={cancelling} onClick={onCancel}>
            {cancelling ? '…' : 'Отменить'}
          </button>
        </div>
      </div>
    );
  }

  // A div (not a <button>) so the «×» can nest as a real button without invalid button-in-button,
  // while the row keeps its `.rd-rep-row + .rd-rep-row` divider. Only live (pending) apps reach here.
  return (
    <div
      className="rd-rep-row rd-app-row"
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
    >
      <span className="rd-ico">{initials}</span>
      <div className="rd-info">
        <div className="rd-ttl">{name}</div>
        {application.createdAt && (
          <div className="rd-met">Подана {formatApplicationDate(application.createdAt)}</div>
        )}
        <div className="rd-met">{STATUS_LABELS[application.status] ?? application.status}</div>
      </div>
      <button
        type="button"
        className="rd-app-cancel"
        aria-label="Отменить заявку"
        onClick={(e) => { e.stopPropagation(); setConfirming(true); }}
      >
        <span aria-hidden="true">✕</span>
      </button>
    </div>
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
          {peerStats.levelTier !== 'base' && (
            <span style={{ marginLeft: 6 }}>
              <LevelPill levelName={peerStats.levelName} tier={peerStats.levelTier} size="sm" />
            </span>
          )}
        </div>
        <div className="rd-met">{club.name} · {formatPeerSignal(peerStats)}</div>
        <div className="rd-met" style={urgent ? { color: 'var(--accent)' } : undefined}>{hoursLabel}</div>
      </div>
    </button>
  );
};

/** «вступил(а) N назад» for a frozen member awaiting their first dues confirmation. */
function formatJoinedRelative(iso: string | null): string {
  if (!iso) return 'ждёт первой оплаты';
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / (1000 * 60 * 60 * 24));
  if (days <= 0) return 'вступил(а) сегодня';
  if (days === 1) return 'вступил(а) вчера';
  if (days < 7) return `вступил(а) ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `вступил(а) ${formatApplicationDate(iso)}`;
}

/** Open the existing organizer profile card (with the dues gate) for a cross-club frozen member. */
function toFrozenMemberStub(dues: OrganizerDuesMemberDto): MemberListItemDto {
  return {
    userId: dues.userId,
    firstName: dues.firstName,
    lastName: dues.lastName,
    avatarUrl: dues.avatarUrl,
    role: 'member',
    joinedAt: dues.joinedAt,
    trust: null,
    promiseFulfillmentPct: null,
    totalConfirmations: null,
    awards: [],
    accessStatus: 'frozen',
    subscriptionExpiresAt: dues.subscriptionExpiresAt,
  };
}

interface AwaitingDuesRowProps {
  item: OrganizerDuesMemberDto;
  onClick: () => void;
}

/** Cross-club «Ждут оплаты» row: a frozen member of one of the caller's clubs. Tap → profile card
 *  where the organizer confirms the dues («Взнос получен»). */
const AwaitingDuesRow: FC<AwaitingDuesRowProps> = ({ item, onClick }) => {
  const fullName = `${item.firstName}${item.lastName ? ` ${item.lastName}` : ''}`;
  const initials = getInitials(fullName) || '·';
  return (
    <button type="button" className="rd-rep-row" onClick={onClick}>
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
        <div className="rd-met">{item.clubName} · {formatJoinedRelative(item.joinedAt)}</div>
      </div>
      <div className="rd-score">
        {item.duesClaimedAt ? (
          <span className="rd-badge rd-going">Оплата заявлена</span>
        ) : (
          <span className="rd-badge rd-warn">Ждёт оплаты</span>
        )}
      </div>
    </button>
  );
};

interface FrozenMembershipRowProps {
  membership: MembershipDto;
  club: ClubDetailDto | undefined;
  onClick: () => void;
  /** Leave the club (called after the inline «точно?» confirm) — undoes an accidental paid join. */
  onLeave: () => void;
  /** This row's leave request is in flight. */
  leaving: boolean;
}

/** Member-side «Доступ закрыт — оплатите»: one of the CALLER's OWN frozen memberships (the organizer
 *  closed access, or the monthly dues window lapsed). Tap → the club page, where «Оплатить взнос» lets
 *  them declare payment. The «×» (with an inline confirm) leaves the club — undo an accidental paid join.
 *  Mirrors the organizer's «Оплата вступления», but from the member's side. */
const FrozenMembershipRow: FC<FrozenMembershipRowProps> = ({ membership, club, onClick, onLeave, leaving }) => {
  const [confirming, setConfirming] = useState(false);
  const name = club?.name ?? `Клуб ${membership.clubId.slice(0, 8)}…`;
  const initials = club ? getInitials(club.name) : '·';
  const claimed = Boolean(membership.duesClaimedAt);
  const priceLine = club && club.subscriptionPrice > 0 ? `Взнос ${club.subscriptionPrice} ₽ / мес` : 'Доступ закрыт';

  if (confirming) {
    return (
      <div className="rd-rep-row rd-app-confirm">
        <div className="rd-info">
          <div className="rd-ttl">Отменить вступление?</div>
          <div className="rd-met">«{name}» — выйдете из клуба.</div>
        </div>
        <div className="rd-app-confirm-acts">
          <button type="button" className="rd-app-confirm-no" disabled={leaving} onClick={() => setConfirming(false)}>
            Нет
          </button>
          <button type="button" className="rd-app-confirm-yes" disabled={leaving} onClick={onLeave}>
            {leaving ? '…' : 'Выйти'}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      className="rd-rep-row rd-app-row"
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } }}
    >
      <span className="rd-ico">
        {club?.avatarUrl ? <img src={club.avatarUrl} alt="" /> : initials}
      </span>
      <div className="rd-info">
        <div className="rd-ttl">{name}</div>
        <div className="rd-met">{priceLine}</div>
        <div className={`rd-met ${claimed ? 'rd-met-ok' : 'rd-met-warn'}`}>
          {claimed ? 'Оплата на проверке' : 'Нужно оплатить'}
        </div>
      </div>
      <button
        type="button"
        className="rd-app-cancel"
        aria-label="Отменить вступление"
        onClick={(e) => { e.stopPropagation(); setConfirming(true); }}
      >
        <span aria-hidden="true">✕</span>
      </button>
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
  const reputationQuery = useMyReputationQuery();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [reviewing, setReviewing] = useState<PendingApplicationDto | null>(null);
  const [duesMember, setDuesMember] = useState<OrganizerDuesMemberDto | null>(null);
  const cancelMutation = useCancelApplicationMutation();
  const leaveMutation = useLeaveClubMutation();

  const myClubs = myClubsQuery.data ?? [];
  // Split the caller's own frozen memberships into a dedicated «Доступ закрыт — оплатите» block so a
  // frozen member sees they've lost access and must pay, instead of the club sitting silently among the
  // active ones. The rest render normally under «Где я состою».
  const frozenMyClubs = useMemo(() => myClubs.filter((m) => m.status === 'frozen'), [myClubs]);
  const activeMyClubs = useMemo(() => myClubs.filter((m) => m.status !== 'frozen'), [myClubs]);
  const applications = applicationsQuery.data ?? [];
  const pendingInbox = pendingInboxQuery.data ?? [];
  const historyClubs = reputationQuery.data?.historyClubs ?? [];
  // Cross-club «Ждут оплаты»: only fetch for users who actually own a club (server returns [] otherwise).
  const isAnyOrganizer = myClubs.some((m) => m.role === 'organizer');
  const awaitingDuesQuery = useOrganizerAwaitingDuesQuery({ enabled: isAnyOrganizer });
  const awaitingDues = awaitingDuesQuery.data ?? [];

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
   * silent (no toast, no haptic), and refetches myClubs so the club appears.
   *
   * Paid clubs are excluded — de-Stars approval creates a `frozen` membership directly, so a paid
   * member already shows under «Где я состою» (no free self-heal needed).
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
  //  - «Мои заявки» (outgoing): only LIVE applications — pending, awaiting the organizer's decision.
  //    Finished-lifecycle apps (rejected / auto_rejected / approved→member) are excluded — they're
  //    history, not actionable. (De-Stars: approval now creates the membership directly, so there's
  //    no "approved-awaiting-payment" limbo anymore.)
  //  - «Заявки в мои клубы» (organizer inbox): pending review.
  const myActiveApps = useMemo(
    () => applications.filter((a) => a.status === 'pending'),
    [applications],
  );
  const myApplicationsCount = myActiveApps.length;
  const organizerInboxCount = pendingInbox.length;

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
        1. «Мои заявки» — applicant-side: pending applications awaiting the organizer's decision.
        2. «Заявки в мои клубы» — organizer-side: pending review.
        3. «Где я состою» — current memberships.
      */}

      {/* 0. My frozen memberships — «Доступ закрыт, оплатите взнос». Highest personal urgency, so it
            leads. Tap → club page, where «Оплатить взнос» declares payment. */}
      {!loading && frozenMyClubs.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-pay">
            🔒 Доступ закрыт — оплатите <span className="rd-count">· {frozenMyClubs.length}</span>
          </div>
          <div className="rd-attn-hint">Здесь доступ закрыт. Оплатите взнос организатору, чтобы вернуть его.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-pay">
            {frozenMyClubs.map((m) => (
              <FrozenMembershipRow
                key={m.id}
                membership={m}
                club={clubDetails[m.clubId]}
                onClick={() => handleClubClick(m.clubId)}
                leaving={leaveMutation.isPending && leaveMutation.variables === m.clubId}
                onLeave={() => {
                  haptic.impact('medium');
                  leaveMutation.mutate(m.clubId, {
                    onSuccess: () => { haptic.notify('success'); setToastMessage('Вы вышли из клуба'); },
                    onError: (e) => { haptic.notify('error'); setToastMessage(e instanceof Error ? e.message : 'Не удалось выйти из клуба'); },
                  });
                }}
              />
            ))}
          </div>
        </>
      )}

      {/* 1. My applications (outgoing) — pending review */}
      {!loading && myApplicationsCount > 0 && (
        <>
          <div className="rd-section-sub-h">
            Мои заявки <span className="rd-count">· {myApplicationsCount}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {myActiveApps.map((app) => (
              <AppCard
                key={app.id}
                application={app}
                club={clubDetails[app.clubId]}
                onClick={() => handleClubClick(app.clubId)}
                cancelling={cancelMutation.isPending && cancelMutation.variables?.applicationId === app.id}
                onCancel={() => {
                  haptic.impact('medium');
                  cancelMutation.mutate(
                    { applicationId: app.id },
                    {
                      onSuccess: () => { haptic.notify('success'); setToastMessage('Заявка отменена'); },
                      onError: (e) => { haptic.notify('error'); setToastMessage(e instanceof Error ? e.message : 'Не удалось отменить заявку'); },
                    },
                  );
                }}
              />
            ))}
          </div>
        </>
      )}

      {/* 2. Applications to my clubs (organizer inbox) — pending review */}
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
          </div>
        </>
      )}

      {/* 2b. Awaiting dues (organizer, cross-club) — frozen members the organizer must admit by
             confirming their off-platform dues: join-by-«Вступить», approved applications, and members
             who already declared payment («Оплата заявлена»). Mirrors «Оплата вступления» inside
             Управление → Участники. */}
      {!loading && awaitingDues.length > 0 && (
        <>
          <div className="rd-section-sub-h rd-attn-pay">
            💸 Оплата вступления <span className="rd-count">· {awaitingDues.length}</span>
          </div>
          <div className="rd-attn-hint">Вступили в ваши клубы — подтвердите взнос, чтобы открыть доступ.</div>
          <div className="rd-glass rd-rep-panel rd-attn-block rd-attn-block-pay">
            {awaitingDues.map((item) => (
              <AwaitingDuesRow
                key={`${item.clubId}:${item.userId}`}
                item={item}
                onClick={() => { haptic.impact('light'); setDuesMember(item); }}
              />
            ))}
          </div>
        </>
      )}

      {/* 3. Active clubs (frozen ones are surfaced in the «Доступ закрыт» block above) */}
      {!loading && activeMyClubs.length > 0 && (
        <>
          <div className="rd-section-sub-h">
            Где я состою <span className="rd-count">· {activeMyClubs.length}</span>
          </div>
          <div className="rd-glass rd-rep-panel">
            {activeMyClubs.map((m) => {
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

      {duesMember && (
        <MemberProfileModal
          member={toFrozenMemberStub(duesMember)}
          clubId={duesMember.clubId}
          isOrganizer
          onClose={() => setDuesMember(null)}
          onActionToast={setToastMessage}
        />
      )}

      {toastMessage && <Toast message={toastMessage} onClose={() => setToastMessage(null)} />}
    </div>
  );
};
