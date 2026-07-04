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

// Русские подписи статусов заявки для строки в карточке.
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

/** Выбор русской формы множественного числа: forms = [один, несколько, много] */
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

// SVG-иконка «человек» для пустого состояния страницы.
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

// Русские подписи категорий клуба (ключ = enum-значение категории с бэкенда).
const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

interface HistoryClubCardProps {
  club: UserClubReputationDto;
  onClick: () => void;
}

/** Клуб, который пользователь покинул, но в котором остался репутационный след («История»). */
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
  /** Отозвать заявку (вызывается после инлайн-подтверждения «точно?»). */
  onCancel: () => void;
  /** Запрос на отзыв заявки этой карточки сейчас выполняется. */
  cancelling: boolean;
}

const AppCard: FC<AppCardProps> = ({ application, club, onClick, onCancel, cancelling }) => {
  // Лёгкое инлайн-подтверждение (без модалки): «×» переключает строку в состояние «Отменить заявку?»
  // с двумя кнопками. Проще попапа и согласуется с другими двухтаповыми деструктивными подтверждениями.
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

  // Именно div (а не <button>), чтобы «×» был настоящей кнопкой без невалидного button-in-button,
  // а строка сохраняла разделитель `.rd-rep-row + .rd-rep-row`. Сюда попадают только живые (pending) заявки.
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

/** «вступил(а) N назад» для frozen-участника, ждущего подтверждения первого взноса. */
function formatJoinedRelative(iso: string | null): string {
  if (!iso) return 'ждёт первой оплаты';
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / (1000 * 60 * 60 * 24));
  if (days <= 0) return 'вступил(а) сегодня';
  if (days === 1) return 'вступил(а) вчера';
  if (days < 7) return `вступил(а) ${days} ${pluralRu(days, ['день', 'дня', 'дней'])} назад`;
  return `вступил(а) ${formatApplicationDate(iso)}`;
}

/** Открывает существующую организаторскую карточку профиля (с dues-гейтом) для frozen-участника из любого клуба. */
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

/** Кросс-клубовая строка «Ждут оплаты»: frozen-участник одного из клубов вызывающего. Тап → карточка
 *  профиля, где организатор подтверждает взнос («Взнос получен»). */
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
  /** Выйти из клуба (вызывается после инлайн-подтверждения «точно?») — откат случайного платного вступления. */
  onLeave: () => void;
  /** Запрос на выход этой строки сейчас выполняется. */
  leaving: boolean;
}

/** Участник-сайд «Доступ закрыт — оплатите»: СОБСТВЕННОЕ frozen-членство вызывающего (организатор
 *  закрыл доступ, либо истекло окно месячного взноса). Тап → страница клуба, где «Оплатить взнос»
 *  позволяет заявить оплату. «×» (с инлайн-подтверждением) — выход из клуба, откат случайного платного
 *  вступления. Зеркалит организаторскую «Оплату вступления», но со стороны участника. */
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
        <div className={`rd-met ${claimed ? 'rd-met-ok' : 'rd-met-soft'}`}>
          {claimed ? 'Оплата на проверке' : 'Не забудьте оплатить взнос'}
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
  // Собственные frozen-членства вызывающего выносим в отдельный блок «Доступ закрыт — оплатите»,
  // чтобы frozen-участник увидел, что потерял доступ и должен оплатить, а не искал клуб, молча
  // висящий среди активных. Остальные рендерятся как обычно в «Где я состою».
  const frozenMyClubs = useMemo(() => myClubs.filter((m) => m.status === 'frozen'), [myClubs]);
  const activeMyClubs = useMemo(() => myClubs.filter((m) => m.status !== 'frozen'), [myClubs]);
  const applications = applicationsQuery.data ?? [];
  const pendingInbox = pendingInboxQuery.data ?? [];
  const historyClubs = reputationQuery.data?.historyClubs ?? [];
  // Кросс-клубовые «Ждут оплаты»: запрашиваем только у владельцев клубов (иначе сервер вернёт []).
  const isAnyOrganizer = myClubs.some((m) => m.role === 'organizer');
  const awaitingDuesQuery = useOrganizerAwaitingDuesQuery({ enabled: isAnyOrganizer });
  const awaitingDues = awaitingDuesQuery.data ?? [];

  const inboxSectionRef = useRef<HTMLDivElement | null>(null);
  // Идемпотентный скролл: deep-link focus=inbox должен проскроллить ровно один раз
  // на маунт, даже когда Vite HMR перезапускает эффект.
  const focusInboxHandledRef = useRef(false);

  const navState = location.state as MyClubsLocationState | null;
  const [toastMessage, setToastMessage] = useState<string | null>(navState?.toast ?? null);
  useEffect(() => {
    if (navState?.toast) {
      window.history.replaceState(null, '', location.pathname);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Deep-link из DM: /my-clubs?focus=inbox. Скроллим к секции инбокса, когда её данные
  // загружены и секция есть в DOM; затем убираем query-параметр, чтобы обновление страницы
  // не сработало повторно. Если у пользователя нет ожидающих заявок — секции нет,
  // просто чистим query.
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
   * Тихий self-heal для застрявших заявок в бесплатные клубы.
   *
   * Предыстория: при одобрении заявки в бесплатный клуб бэкенд создаёт членство
   * inline (см. free-ветку ApplicationService.approveApplication). Если эта ветка
   * когда-то падала по legacy-причинам — или одобрение прошло по кодпасу до фикса —
   * у пользователя status=approved, но строки членства нет, и клуб не показывается
   * в «Активные клубы». Он не догадается нажать «Завершить вступление» на ClubPage.
   *
   * Фикс: на маунте страницы, когда оба запроса завершились, находим такие застрявшие
   * заявки (approved + бесплатный клуб + вызывающий не в активном членстве) и вызываем
   * completeFreeMembership(applicationId) по разу для каждой. Мутация идемпотентна
   * на сервере (400 «Already a member», если гонка успела создать настоящее членство),
   * тихая (без тоста и haptic) и рефетчит myClubs, чтобы клуб появился.
   *
   * Платные клубы исключены — de-Stars-одобрение сразу создаёт `frozen`-членство, так что платный
   * участник уже виден в «Где я состою» (free-self-heal не нужен).
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
    // Нужны детали клуба, чтобы понять, subscriptionPrice === 0 или нет.
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

  // Инбокс сгруппирован по адресату (см. docs/modules/my-clubs-unified.md):
  //  - «Мои заявки» (исходящие): только ЖИВЫЕ заявки — pending, ждут решения организатора.
  //    Заявки с завершённым жизненным циклом (rejected / auto_rejected / approved→member) исключены —
  //    это история, действий не требует. (De-Stars: одобрение теперь создаёт членство напрямую,
  //    лимба «approved-awaiting-payment» больше нет.)
  //  - «Заявки в мои клубы» (инбокс организатора): ждут рассмотрения.
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
      {/* Шапка */}
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

      {/* Спиннер загрузки */}
      {loading && (
        <div className="rd-spinner-row">
          <Spinner size="m" />
        </div>
      )}

      {/* Пустое состояние */}
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
        Секции сгруппированы по адресату (см. docs/modules/my-clubs-unified.md):
        1. «Мои заявки» — сторона заявителя: pending-заявки, ждущие решения организатора.
        2. «Заявки в мои клубы» — сторона организатора: ждут рассмотрения.
        3. «Где я состою» — текущие членства.
      */}

      {/* 0. Мои frozen-членства — «Доступ закрыт, оплатите взнос». Максимальная личная срочность,
            поэтому идёт первым. Тап → страница клуба, где «Оплатить взнос» заявляет оплату. */}
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

      {/* 1. Мои заявки (исходящие) — ждут рассмотрения */}
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

      {/* 2. Заявки в мои клубы (инбокс организатора) — ждут рассмотрения */}
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

      {/* 2b. Ждут оплаты (организатор, кросс-клуб) — frozen-участники, которых организатор впускает,
             подтверждая их внеплатформенный взнос: вступившие по «Вступить», одобренные заявки и те,
             кто уже заявил оплату («Оплата заявлена»). Зеркалит «Оплату вступления» внутри
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

      {/* 3. Активные клубы (frozen-члены показаны выше, в блоке «Доступ закрыт») */}
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


      {/* 4. История — клубы, которые пользователь покинул, но репутационный след остался */}
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
