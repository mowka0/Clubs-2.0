import { FC, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Spinner, Placeholder, Modal } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useSetClubContext } from '../store/useClubContextStore';
import {
  useApplyToClubMutation,
  useClubQuery,
  useJoinClubMutation,
  useLeaveClubMutation,
  useLeavePreviewQuery,
  useMyClubsQuery,
} from '../queries/clubs';
import {
  useCompleteFreeMembershipMutation,
  useMyApplicationsQuery,
} from '../queries/applications';
import { ApiError } from '../api/apiClient';
import { formatPrice } from '../utils/formatters';
import { isActiveManagerMembership } from '../utils/membershipRole';
import { ClubActivitiesTab } from '../components/club/ClubActivitiesTab';
import { ClubCoverButton } from '../components/club/ClubCoverButton';
import { ClubIdentityHeader } from '../components/club/ClubIdentityHeader';
import { ClubLockedNotice } from '../components/club/ClubLockedNotice';
import { ClubChatConnectBanner } from '../components/club/ClubChatConnectBanner';
import { ClubEventsTeaser } from '../components/club/ClubEventsTeaser';
import { WelcomeScene, memberCountCaption } from '../components/onboarding/WelcomeScene';
import { useCompleteTourMutation } from '../queries/profile';
import { ClubMembersTab } from '../components/club/ClubMembersTab';
import { ClubQualityFacts } from '../components/club/ClubQualityFacts';
import { DuesPaymentSheet } from '../components/club/DuesPaymentSheet';
import { InviteSheet } from '../components/club/InviteSheet';
import { LeaveClubModal } from '../components/club/LeaveClubModal';
import { CoachTour } from '../components/onboarding/CoachTour';
import { ClubChatPill } from '../components/club/ClubChatPill';

type TabId = 'activities' | 'members';

interface TabItem {
  key: TabId;
  label: string;
  selected: boolean;
}

function formatExpiryDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

const LeaveIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <path d="M16 17l5-5-5-5" />
    <path d="M21 12H9" />
  </svg>
);

/** Шестерёнка в шапке — вход менеджера в «Управление» (занимает место кнопки выхода у участника). */
const ManageIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

export const ClubPage: FC = () => {
  useBackButton(true);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const haptic = useHaptic();
  const { user, setUser } = useAuthStore();
  useSetClubContext(id);

  // club-invites (кадр E, momentum): после создания клуба MyClubsPage ведёт сюда с
  // state.openInvite — открываем таб «Участники» с уже открытым шитом приглашения.
  const openInviteOnMount = !!(location.state as { openInvite?: boolean } | null)?.openInvite;

  const clubQuery = useClubQuery(id);
  const myClubsQuery = useMyClubsQuery();
  const applicationsQuery = useMyApplicationsQuery();

  const joinMutation = useJoinClubMutation();
  const applyMutation = useApplyToClubMutation();
  const completeFreeMutation = useCompleteFreeMembershipMutation();
  const leaveMutation = useLeaveClubMutation();
  const completeWelcome = useCompleteTourMutation();

  const [joinError, setJoinError] = useState<string | null>(null);
  const [showApplyModal, setShowApplyModal] = useState(false);
  const [showInviteSheet, setShowInviteSheet] = useState(openInviteOnMount);
  const [answerText, setAnswerText] = useState('');
  // De-Stars: вступление возвращает MembershipDto. В платном клубе membership попадает в `frozen`
  // (нет доступа, пока организатор не подтвердит офлайн-взнос); в бесплатном — сразу `active`.
  // Запоминаем статус из результата мутации, чтобы CTA среагировал раньше, чем придёт рефетч membership.
  const [joinedStatus, setJoinedStatus] = useState<string | null>(null);
  // Велком-сцена (онбординг, срез 3): оверлей после первого вступления новичка. Точка входа
  // любая (deep-link события → клуб → «Вступить», каталог) — сцена одна на первый клуб.
  const [showWelcome, setShowWelcome] = useState(false);
  const [showDuesSheet, setShowDuesSheet] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>(openInviteOnMount ? 'members' : 'activities');
  const [showLeaveModal, setShowLeaveModal] = useState(false);
  const [leaveError, setLeaveError] = useState<string | null>(null);

  const club = clubQuery.data;
  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];

  const membership = myClubs.find((m) => m.clubId === id);
  // «Выйти» — это мягкая отмена подписки для всех, у кого ещё идёт оплаченный период, включая
  // клуб, ставший из платного бесплатным (price 0, но у membership есть будущий subscription_expires_at).
  // Только по-настоящему бесплатное членство идёт по жёсткому пути «выход с обязательствами». Повторяет
  // backend-роутинг (MembershipService.hasActivePaidAccess), чтобы UI соответствовал реальному штрафу.
  const hasActivePaidAccess =
    (!!club && club.subscriptionPrice > 0)
    || (!!membership?.subscriptionExpiresAt
      && new Date(membership.subscriptionExpiresAt).getTime() > Date.now());
  // Число обязательств для диалога выхода — запрашивается только для по-настоящему бесплатного
  // выхода, пока модалка открыта (платный/в-периоде выход ничего не ломает). Размеры штрафов
  // остаются внутренними для сервера.
  const leavePreviewQuery = useLeavePreviewQuery(id, showLeaveModal && !hasActivePaidAccess);

  const isOwner = !!club && club.ownerId === user?.id;
  // Менеджер клуба (co-organizers): владелец ИЛИ активный со-организатор — видит таб «Управление»,
  // строку приглашений и организаторский вид ростера. Fail-close: у замороженного/просроченного
  // со-орга роль в membership остаётся, но manager-UI скрывается (бэкенд в этом состоянии отдаёт 403).
  const isManager = isOwner || isActiveManagerMembership(membership);
  // Active membership = полноценный участник; отменённое платное membership внутри своего
  // оплаченного периода = «всё ещё в клубе» — табы остаются видимыми, но вместо
  // «Выйти из клуба» в футере показывается read-only заметка «Подписка отменена».
  const isActiveMember = !!membership && membership.status === 'active';
  const isCancelledInPeriod =
    !!membership
    && membership.status === 'cancelled'
    && !!membership.subscriptionExpiresAt
    && new Date(membership.subscriptionExpiresAt).getTime() > Date.now();
  // De-Stars: платный участник, который вступил, но ещё не допущен (организатор не подтвердил
  // офлайн-взнос). Он уже внутри клуба, но без доступа к контенту — без табов, с заметкой «в ожидании».
  const isFrozenMember = membership?.status === 'frozen' || joinedStatus === 'frozen';
  // Просрочил продление (шедулер: active → expired): всё ещё участник-должник, доступ закрыт до
  // нового взноса. Вид зеркалит frozen (тот же claim-флоу), но текст говорит о продлении, не вступлении.
  const isExpiredMember = membership?.status === 'expired';
  const isMember = isActiveMember || isCancelledInPeriod;
  const myApplication = applications.find((a) => a.clubId === id) ?? null;

  const joining = joinMutation.isPending || applyMutation.isPending || completeFreeMutation.isPending;

  // `?pay=1` — приход по кнопке «Оплатить взнос» из DM: она обязана давать оплату, а не экран,
  // на котором её надо ещё раз найти. Шит открываем, как только стало известно, что человек
  // действительно должник (membership грузится асинхронно), и ровно один раз — параметр гасим,
  // иначе он снова сработает при возврате назад.
  const [searchParams, setSearchParams] = useSearchParams();
  const duesParamHandled = useRef(false);
  useEffect(() => {
    if (duesParamHandled.current || searchParams.get('pay') !== '1') return;
    if (myClubsQuery.isPending) return;
    duesParamHandled.current = true;
    setSearchParams({}, { replace: true });
    if ((isFrozenMember || isExpiredMember) && !membership?.duesClaimedAt) setShowDuesSheet(true);
  }, [
    searchParams, setSearchParams, myClubsQuery.isPending,
    isFrozenMember, isExpiredMember, membership?.duesClaimedAt,
  ]);

  if (clubQuery.isPending) {
    return (
      <div className="rd-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (clubQuery.error || !club) {
    return (
      <div className="rd-page">
        <Placeholder header="Ошибка" description={clubQuery.error?.message ?? 'Клуб не найден'} />
      </div>
    );
  }

  const handleJoin = () => {
    if (!id) return;
    haptic.impact('medium');
    setJoinError(null);
    joinMutation.mutate(id, {
      onSuccess: (membership) => {
        setJoinedStatus(membership.status);
        haptic.notify('success');
        // Самое первое вступление в жизни аккаунта → велком-сцена вместо голой страницы.
        if (user !== null && !user.onboardingTours.includes('WELCOME')) setShowWelcome(true);
      },
      onError: (e) => {
        // 409 — тихое восстановление: кэш уже инвалидирован, UI был просто устаревшим.
        if (e instanceof ApiError && e.status === 409) return;
        setJoinError(e.message);
        haptic.notify('error');
      },
    });
  };

  // CTA велком-сцены: помечаем тур WELCOME и закрываем оверлей — мы уже на странице клуба,
  // навигация не нужна. mutateAsync ждём до setUser: упавший запрос не должен молча оставить
  // тур неотмеченным (иначе сцена вылезла бы при следующем вступлении).
  const handleWelcomeCta = async () => {
    if (completeWelcome.isPending) return;
    haptic.impact('medium');
    try {
      const freshUser = await completeWelcome.mutateAsync('WELCOME');
      setUser(freshUser);
      setShowWelcome(false);
    } catch {
      haptic.notify('error');
      // Не запираем человека на сцене из-за сети: клуб уже открыт под оверлеем, закрываем.
      // Тур остался неотмеченным — сцену он увидит ещё раз, это честнее тупика.
      setShowWelcome(false);
    }
  };

  const handleApply = () => {
    if (!id) return;
    if (club.applicationQuestion && !answerText.trim()) {
      setJoinError('Введите ответ на вопрос');
      return;
    }
    haptic.impact('medium');
    setJoinError(null);
    applyMutation.mutate(
      { clubId: id, answerText: answerText.trim() },
      {
        onSuccess: () => {
          setShowApplyModal(false);
          haptic.notify('success');
        },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) {
            setShowApplyModal(false);
            return;
          }
          setJoinError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  // Обработчик восстановления для устаревшего «застрявшего» состояния: заявка в бесплатный клуб
  // была одобрена, но ветка автосоздания membership так и не отработала (старый баг / уже
  // существующие данные). Backend `complete-free-membership` идемпотентно пересоздаёт membership;
  // при успехе страница перерисовывается с табами участника.
  const handleCompleteFreeMembership = () => {
    if (!id || !myApplication) return;
    haptic.impact('medium');
    setJoinError(null);
    completeFreeMutation.mutate(
      { applicationId: myApplication.id, clubId: id },
      {
        onSuccess: () => {
          haptic.notify('success');
        },
        onError: (e) => {
          setJoinError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleOpenLeaveModal = () => {
    haptic.impact('light');
    setLeaveError(null);
    setShowLeaveModal(true);
  };

  const handleConfirmLeave = () => {
    if (!id || !club) return;
    setLeaveError(null);
    haptic.impact('medium');
    leaveMutation.mutate(id, {
      onSuccess: () => {
        haptic.notify('success');
        setShowLeaveModal(false);
        if (hasActivePaidAccess) {
          // Мягкая отмена сохраняет строку membership (status=cancelled,
          // subscription_expires_at в будущем) — остаёмся на странице клуба, чтобы
          // баннер «отменено» сменил иконку выхода.
          return;
        }
        navigate('/my-clubs', {
          replace: true,
          state: { toast: `Вы вышли из клуба «${club.name}»` },
        });
      },
      onError: (e) => {
        haptic.notify('error');
        setLeaveError(e.message);
      },
    });
  };

  const handleTabClick = (tab: TabId) => {
    haptic.select();
    setActiveTab(tab);
  };

  /** «Управление» переехало из таба в шестерёнку шапки (решение PO 2026-07-30). */
  const handleOpenManage = () => {
    haptic.impact('light');
    navigate(`/clubs/${id}/manage`);
  };

  const renderCta = () => {
    // Свежее вступление в этой сессии всегда побеждает (membership-запрос может ещё рефетчиться).
    if (joinedStatus === 'active') {
      return (
        <button type="button" className="rd-btn-outline" disabled>
          Вы вступили
        </button>
      );
    }
    // Участник, вышедший «в отмену» (исключён / отклонён / вышел, без платной отсрочки), должен получить
    // свежий CTA вступления/заявки — игнорируем устаревшую/осиротевшую заявку, оставшуюся от прошлого
    // цикла membership, иначе он застрянет на «Заявка одобрена» (backend теперь тоже чистит заявку
    // при отмене; это — защита на стороне UI).
    const wasMemberNowOut = membership?.status === 'cancelled' && !isCancelledInPeriod;
    if (!wasMemberNowOut && myApplication?.status === 'pending') {
      return (
        <button type="button" className="rd-btn-outline" disabled>
          Заявка на рассмотрении
        </button>
      );
    }
    if (!wasMemberNowOut && myApplication?.status === 'approved') {
      const price = club.subscriptionPrice ?? 0;
      if (price <= 0) {
        // Устаревшее «застрявшее» состояние: бесплатный клуб, заявка одобрена, но строки membership нет.
        // Показываем CTA восстановления, чтобы пользователь мог завершить вступление.
        return (
          <>
            <button
              type="button"
              className="rd-btn-primary"
              onClick={handleCompleteFreeMembership}
              disabled={joining}
            >
              {joining ? <Spinner size="s" /> : 'Завершить вступление'}
            </button>
            <div className="rd-cta-hint">
              Заявка одобрена. Нажмите чтобы вступить.
            </div>
          </>
        );
      }
      // Платный клуб: одобрение теперь сразу создаёт frozen-membership (de-Stars), поэтому обычно
      // вместо этого рендерится заметка «frozen-pending» выше. Здесь — запасной текст для старых
      // одобренных строк.
      return (
        <button type="button" className="rd-btn-outline" disabled>
          Заявка одобрена — организатор откроет доступ
        </button>
      );
    }
    // club-invites (кадр F): клуб полон — прямое вступление невозможно при любом типе доступа,
    // витрина деградирует в заявку-«просьбу расширить». Решение за организатором (инбокс).
    if (club.memberCount >= club.memberLimit) {
      return (
        <>
          <div className="rd-cl-chip">
            <span aria-hidden="true">👥</span>
            <span>Клуб сейчас полон — вы всё равно можете попроситься, организатор может расширить клуб</span>
          </div>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={() => {
              // Есть вопрос клуба → обычная модалка заявки; нет — подаём сразу.
              if (club.applicationQuestion) { haptic.impact('light'); setShowApplyModal(true); return; }
              handleApply();
            }}
            disabled={joining}
          >
            {joining ? <Spinner size="s" /> : 'Попроситься в клуб'}
          </button>
          <div className="rd-cta-hint">Заявка попадёт к организатору — он решает, расширять ли клуб</div>
        </>
      );
    }
    if (club.accessType === 'open') {
      const isPaid = (club.subscriptionPrice ?? 0) > 0;
      return (
        <>
          <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
            {joining ? <Spinner size="s" /> : 'Вступить'}
          </button>
          {isPaid && (
            <div className="rd-cta-hint">
              После вступления передайте взнос организатору — он откроет доступ.
            </div>
          )}
        </>
      );
    }
    if (club.accessType === 'closed') {
      return (
        <>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={() => { haptic.impact('light'); setShowApplyModal(true); }}
          >
            Хочу вступить
          </button>
          <div className="rd-cta-hint">
            Организатор задаст один вопрос. Ответ увидит только он.
          </div>
        </>
      );
    }
    return null;
  };

  /**
   * Кнопка из подсказки чата у гостя: ведёт туда же, куда основная кнопка вступления —
   * закрытый клуб через форму заявки, открытый вступает сразу. Дублировать здесь логику
   * CTA нельзя: разойдутся при первом же изменении правил вступления.
   */
  const handleChatHintCta = () => {
    haptic.impact('light');
    if (club.accessType === 'closed') {
      setShowApplyModal(true);
      return;
    }
    handleJoin();
  };

  const showLeaveIcon = !isOwner && isActiveMember;
  const showCancelledNote = !isOwner && isCancelledInPeriod && membership?.subscriptionExpiresAt;

  const leaveVariant: 'free' | 'paid' = hasActivePaidAccess ? 'paid' : 'free';
  const leavePaidUntilLabel = membership?.subscriptionExpiresAt
    ? formatExpiryDate(membership.subscriptionExpiresAt)
    : null;

  const showTabs = isMember || isManager;

  const tabItems: TabItem[] = [
    { key: 'activities', label: 'Активности', selected: activeTab === 'activities' },
    { key: 'members', label: 'Участники', selected: activeTab === 'members' },
  ];

  return (
    <div className="rd-page">
      {/* Шапка (обложка → аватар → название → чипы) общая с посадочной приглашения —
          см. ClubIdentityHeader. Здесь в угол обложки уезжают кнопки роли. */}
      <div data-coach="club-identity">
      <ClubIdentityHeader
        club={club}
        avatarEditable={isManager}
        coverActions={
          <>
            {/* Менеджеру — смена обложки; аватар меняется тапом по самому кружку ниже. */}
            {isManager && <ClubCoverButton clubId={club.id} hasCover={!!club.coverUrl} />}
            {/* Одно место под роль: менеджеру — вход в «Управление», участнику — выход из клуба.
                Обе роли одновременно невозможны (владелец из клуба не выходит), поэтому кнопка одна. */}
            {isManager ? (
              <button
                type="button"
                className="rd-hero-btn"
                data-coach="club-manage"
                onClick={handleOpenManage}
                aria-label="Управление клубом"
                title="Управление клубом"
              >
                <ManageIcon />
              </button>
            ) : showLeaveIcon ? (
              <button
                type="button"
                className="rd-hero-btn"
                onClick={handleOpenLeaveModal}
                aria-label="Выйти из клуба"
                title="Выйти из клуба"
              >
                <LeaveIcon />
              </button>
            ) : null}
          </>
        }
      />
      </div>

      {showCancelledNote && membership?.subscriptionExpiresAt && (
        <div className="rd-note" role="status">
          Подписка отменена · доступ до {formatExpiryDate(membership.subscriptionExpiresAt)}
        </div>
      )}

      {/* Панель подключения чата (club-chat-link): владельцу клуба без привязанного чата.
         Именно isOwner, а не isManager — все эндпоинты привязки владельческие
         (ChatLinkService.requireOwner), у со-организатора таба «Чат» нет вообще. */}
      {isOwner && !club.chatLinked && (
        // key={club.id}: страница не перемонтируется при переходе между клубами,
        // ключ гарантирует свежее состояние скрытия для каждого клуба.
        <ClubChatConnectBanner key={club.id} clubId={club.id} />
      )}

      {/* О клубе — описание, правила и вход в чат одним блоком (решение PO 2026-07-30):
          отдельные секции «Правила» и широкая кнопка чата упразднены. */}
      <div className="rd-section-sub-h">О клубе</div>
      <div className="rd-club-about" data-coach="club-about">
        <div className="rd-txt">{club.description}</div>
        {club.rules && (
          <>
            <div className="rd-rules-h">Правила</div>
            <div className="rd-txt">{club.rules}</div>
          </>
        )}
        {/* Вход в чат по door-ссылке (club-chat-link): участник уже в чате → Telegram просто
            откроет его. Гость видит ту же пилюлю, но она ведёт к подсказке: двери у него ещё
            нет, а сам факт чата — довод вступить (решение PO 2026-07-31; раньше гостю
            доставалась лишь пассивная строчка у кнопки вступления). */}
        {showTabs && club.chatInviteLink && (
          <ClubChatPill mode="open" inviteLink={club.chatInviteLink} />
        )}
        {!showTabs && club.chatLinked && (
          <ClubChatPill
            mode="hint"
            hintText={
              club.chatDoorEnabled
                ? 'Чат клуба открыт участникам. Вступите — и бот впустит вас туда.'
                : 'У клуба есть чат. Организатор позовёт вас туда после вступления.'
            }
            ctaLabel={club.accessType === 'closed' ? 'Хочу вступить' : 'Вступить в клуб'}
            onCta={handleChatHintCta}
          />
        )}
      </div>

      {/* Жизнь клуба — кольца качества + подпись возраст/активность, видны всем */}
      {id && (
        <div data-coach="club-life">
          <ClubQualityFacts clubId={id} memberCount={club.memberCount} />
        </div>
      )}

      {/* Участник без доступа: frozen (вступил, ждёт подтверждения первого взноса) или expired
          (подписка истекла — должник по продлению). Один claim-флоу, разные тексты. */}
      {!showTabs && (isFrozenMember || isExpiredMember) && (
        <>
          <ClubLockedNotice
            title={isExpiredMember ? 'Подписка истекла' : 'Вы вступили в клуб'}
            description={
              isExpiredMember
                ? 'Доступ к активностям закрыт. Продлите взнос организатору — и он снова откроет доступ.'
                : 'Доступ к активностям откроет организатор после того, как вы передадите ему взнос.'
            }
          />

          {/* Тизер-афиша (PO 2026-07-24): участник без взноса видит, что клуб живой, —
              главный аргумент передать взнос. Урезанная проекция без места/фото/состава. */}
          <ClubEventsTeaser
            clubId={club.id}
            lockHint="Место встреч, голосование и участие откроются после взноса"
          />

          {membership?.duesClaimedAt ? (
            <div className="rd-glass rd-dues-pending">
              <span aria-hidden="true">⏳</span>
              <div>
                <strong>Оплата на проверке</strong>
                <span>
                  Вы заявили об оплате{membership.duesClaimMethod === 'cash' ? ' наличными' : ' по СБП'}.
                  Организатор проверит и откроет доступ.
                </span>
              </div>
            </div>
          ) : (
            <div className="rd-cta-wrap">
              <button type="button" className="rd-btn-primary" onClick={() => { haptic.impact('medium'); setShowDuesSheet(true); }}>
                Оплатить взнос
              </button>
              <div className="rd-cta-hint">Оплата идёт напрямую организатору. После оплаты он откроет доступ.</div>
            </div>
          )}
        </>
      )}

      {showDuesSheet && (
        <DuesPaymentSheet
          clubId={club.id}
          price={club.subscriptionPrice}
          paymentLink={club.paymentLink}
          paymentMethodNote={club.paymentMethodNote}
          onClose={() => setShowDuesSheet(false)}
          onClaimed={() => setShowDuesSheet(false)}
        />
      )}

      {/* Гость: заглушка с замком + CTA */}
      {!showTabs && !isFrozenMember && !isExpiredMember && (
        <>
          <ClubLockedNotice
            title="Активности клуба доступны участникам"
            description="Содержимое клуба открывается после вступления."
          />

          {/* Тизер-афиша (PO 2026-07-24): гость видит ритм жизни клуба до вступления/оплаты. */}
          <ClubEventsTeaser
            clubId={club.id}
            lockHint={
              club.subscriptionPrice > 0
                ? 'Место встреч, голосование и участие откроются после вступления и взноса'
                : 'Место встреч, голосование и участие откроются после вступления'
            }
          />

          {joinError && <div className="rd-error">{joinError}</div>}

          <div className="rd-cta-wrap">
            {renderCta()}
          </div>
        </>
      )}

      {/* Участник / Организатор: табы с учётом роли */}
      {showTabs && id && (
        <>
          <div className="rd-seg" role="tablist">
            {tabItems.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`rd-seg-btn${item.selected ? ' rd-active' : ''}`}
                data-coach={item.key === 'activities' ? 'club-tab-activities' : 'club-tab-members'}
                onClick={() => handleTabClick(item.key)}
              >
                {item.label}
              </button>
            ))}
          </div>

          {activeTab === 'activities' && <ClubActivitiesTab clubId={id} isManager={isManager} />}
          {/* managementView={isManager}: менеджеру (владелец/со-орг) здесь показываются attention-бакеты
              «Скоро закончится» / «Оплата вступления» (раньше жили в дублирующем табе «Управление»,
              теперь участники только тут). Обычный участник видит плоский список — бакеты за гейтом. */}
          {activeTab === 'members' && (
            <>
              {/* club-invites (кадр A): личные приглашения — вход там, где виден состав клуба.
                  С 2026-07-30 зовёт ЛЮБОЙ участник, не только менеджер (решение PO): гейт на
                  бэкенде — членство, поэтому отдельной ролевой проверки здесь нет. */}
              <button
                type="button"
                className="rd-invite-row"
                onClick={() => { haptic.impact('light'); setShowInviteSheet(true); }}
              >
                <span className="rd-invite-plus" aria-hidden="true">＋</span>
                <span className="rd-invite-txt">
                  <b>Пригласить в клуб</b>
                  <span>Отправьте приглашение от своего имени</span>
                </span>
              </button>
              <ClubMembersTab clubId={id} isOrganizer={isManager} isOwner={isOwner} managementView={isManager} />
            </>
          )}
        </>
      )}

      {showInviteSheet && id && (
        <InviteSheet clubId={id} onClose={() => setShowInviteSheet(false)} />
      )}

      {/* Модалка заявки (флоу гостя: закрытый клуб или «Попроситься» в полный) */}
      {showApplyModal && (
        <Modal open onOpenChange={(open) => !open && setShowApplyModal(false)}>
          <div className="rd-modal-form" style={{ padding: 16 }}>
            <h3 style={{ fontSize: 18, fontWeight: 700, margin: '0 0 14px' }}>
              Заявка в клуб
            </h3>
            {club.applicationQuestion && (
              <label className="rd-field" style={{ marginBottom: 12 }}>
                <span className="rd-label">{club.applicationQuestion}</span>
                <input
                  className="rd-input"
                  placeholder="Ваш ответ"
                  value={answerText}
                  onChange={(e) => setAnswerText(e.target.value)}
                />
              </label>
            )}
            {joinError && <div className="rd-error" style={{ textAlign: 'left' }}>{joinError}</div>}
            <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
              <button
                type="button"
                className="rd-btn-outline"
                style={{ flex: 1 }}
                onClick={() => setShowApplyModal(false)}
              >
                Отмена
              </button>
              <button
                type="button"
                className="rd-btn-primary"
                style={{ flex: 1 }}
                onClick={handleApply}
                disabled={joining}
              >
                {joining ? <Spinner size="s" /> : 'Отправить'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      <LeaveClubModal
        open={showLeaveModal}
        clubName={club.name}
        variant={leaveVariant}
        paidUntilLabel={leavePaidUntilLabel}
        obligationsCount={leavePreviewQuery.data?.totalObligations ?? 0}
        obligationsLoading={leavePreviewQuery.isLoading}
        submitting={leaveMutation.isPending}
        errorMessage={leaveError}
        onConfirm={handleConfirmLeave}
        onClose={() => {
          if (leaveMutation.isPending) return;
          setShowLeaveModal(false);
          setLeaveError(null);
        }}
      />

      {/* Велком-сцена новичка (онбординг, срез 3): полноэкранный оверлей после первого
          вступления. CTA помечает онбординг и закрывает сцену — страница клуба уже под ней. */}
      {showWelcome && (
        <WelcomeScene
          variant={club.subscriptionPrice > 0 ? 'paid' : 'free'}
          clubName={club.name}
          clubCaption={`${club.city} · ${club.subscriptionPrice > 0 ? formatPrice(club.subscriptionPrice) : memberCountCaption(club.memberCount)}`}
          clubAvatarUrl={club.avatarUrl}
          ctaPending={completeWelcome.isPending}
          onCta={handleWelcomeCta}
        />
      )}

      {/* Тур клуба. Владельцу — свой, более подробный (те же блоки плюс вход в настройки):
          он только что создал клуб, и ему нужно донастроить своё, а не осмотреться в чужом.
          Рендерится ровно один — у двух одновременных туров подрались бы затемнения.
          Пока висит велком-сцена, подсказки не лезут: она перекрывает страницу целиком. */}
      <CoachTour tour={isOwner ? 'CLUB_OWNER' : 'CLUB'} ready={!showWelcome} />
    </div>
  );
};
