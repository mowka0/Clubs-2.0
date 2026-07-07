import { FC, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Spinner,
  Placeholder,
  Input,
  Modal,
  Section,
  Text,
} from '@telegram-apps/telegram-ui';
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
import { openTmeLink } from '../utils/telegramLinks';
import { ClubActivitiesTab } from '../components/club/ClubActivitiesTab';
import { ClubMembersTab } from '../components/club/ClubMembersTab';
import { ClubQualityFacts } from '../components/club/ClubQualityFacts';
import { DuesPaymentSheet } from '../components/club/DuesPaymentSheet';
import { LeaveClubModal } from '../components/club/LeaveClubModal';

const ACCESS_LABELS: Record<string, string> = {
  open: 'Открытый', closed: 'По заявке', private: 'Приватный',
};

type TabId = 'activities' | 'members';
type TabKey = TabId | 'manage';

interface TabItem {
  key: TabKey;
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

const LockIcon: FC = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="11" width="16" height="11" rx="2.5" />
    <path d="M8 11V7a4 4 0 1 1 8 0v4" />
  </svg>
);

const LeaveIcon: FC = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <path d="M16 17l5-5-5-5" />
    <path d="M21 12H9" />
  </svg>
);

export const ClubPage: FC = () => {
  useBackButton(true);
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { user } = useAuthStore();
  useSetClubContext(id);

  const clubQuery = useClubQuery(id);
  const myClubsQuery = useMyClubsQuery();
  const applicationsQuery = useMyApplicationsQuery();

  const joinMutation = useJoinClubMutation();
  const applyMutation = useApplyToClubMutation();
  const completeFreeMutation = useCompleteFreeMembershipMutation();
  const leaveMutation = useLeaveClubMutation();

  const [joinError, setJoinError] = useState<string | null>(null);
  const [showApplyModal, setShowApplyModal] = useState(false);
  const [answerText, setAnswerText] = useState('');
  // De-Stars: вступление возвращает MembershipDto. В платном клубе membership попадает в `frozen`
  // (нет доступа, пока организатор не подтвердит офлайн-взнос); в бесплатном — сразу `active`.
  // Запоминаем статус из результата мутации, чтобы CTA среагировал раньше, чем придёт рефетч membership.
  const [joinedStatus, setJoinedStatus] = useState<string | null>(null);
  const [showDuesSheet, setShowDuesSheet] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>('activities');
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
  const isOrganizer = isOwner || membership?.role === 'organizer';
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
      },
      onError: (e) => {
        // 409 — тихое восстановление: кэш уже инвалидирован, UI был просто устаревшим.
        if (e instanceof ApiError && e.status === 409) return;
        setJoinError(e.message);
        haptic.notify('error');
      },
    });
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

  // Таб «Управление» — это ссылка-переход, а не переключатель состояния: тап вызывает haptic
  // impact (не select) и ведёт на /manage. activeTab никогда не содержит 'manage'.
  const handleTabClick = (tab: TabKey) => {
    if (tab === 'manage') {
      haptic.impact('light');
      navigate(`/clubs/${id}/manage`);
      return;
    }
    haptic.select();
    setActiveTab(tab);
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

  const showLeaveIcon = !isOwner && isActiveMember;
  const showCancelledNote = !isOwner && isCancelledInPeriod && membership?.subscriptionExpiresAt;

  const leaveVariant: 'free' | 'paid' = hasActivePaidAccess ? 'paid' : 'free';
  const leavePaidUntilLabel = membership?.subscriptionExpiresAt
    ? formatExpiryDate(membership.subscriptionExpiresAt)
    : null;

  const showTabs = isMember || isOrganizer;
  const roleBadgeLabel = isOrganizer ? 'Вы организатор' : isMember ? 'Вы участник' : null;

  const tabItems: TabItem[] = [
    { key: 'activities', label: 'Активности', selected: activeTab === 'activities' },
    { key: 'members', label: 'Участники', selected: activeTab === 'members' },
  ];
  if (isOrganizer) {
    tabItems.push({ key: 'manage', label: 'Управление', selected: false });
  }

  const heroMeta = [
    ACCESS_LABELS[club.accessType] ?? club.accessType,
    club.city,
    `${club.memberCount} / ${club.memberLimit}`,
    formatPrice(club.subscriptionPrice),
  ].filter(Boolean).join(' · ');

  return (
    <div className="rd-page">
      {/* Обложка (hero) */}
      <div className="rd-hero rd-compact">
        <div
          className="rd-hero-bg"
          data-cat={club.category}
          style={club.avatarUrl ? { backgroundImage: `url(${club.avatarUrl})` } : undefined}
        />
        {showLeaveIcon && (
          <button
            type="button"
            className="rd-hero-btn rd-right"
            onClick={handleOpenLeaveModal}
            aria-label="Выйти из клуба"
            title="Выйти из клуба"
          >
            <LeaveIcon />
          </button>
        )}
        <div className="rd-hero-meta">
          {roleBadgeLabel && (
            <div className="rd-hero-type-badge">{roleBadgeLabel.toUpperCase()}</div>
          )}
          <div className="rd-hero-ttl">{club.name}</div>
          <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>{heroMeta}</div>
        </div>
      </div>

      {showCancelledNote && membership?.subscriptionExpiresAt && (
        <div className="rd-note" role="status">
          Подписка отменена · доступ до {formatExpiryDate(membership.subscriptionExpiresAt)}
        </div>
      )}

      {/* О клубе */}
      <div className="rd-section-sub-h">О клубе</div>
      <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
        <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.description}</div>
      </div>

      {/* Чат клуба (club-chat-link): участнику с доступом — кнопка входа по door-ссылке
          (уже в чате → Telegram просто откроет чат; ещё нет → заявка + авто-впуск ботом). */}
      {showTabs && club.chatInviteLink && (
        <button
          type="button"
          className="rd-btn-outline"
          style={{ marginBottom: 14 }}
          onClick={() => {
            if (!club.chatInviteLink) return;
            haptic.impact('light');
            openTmeLink(club.chatInviteLink);
          }}
        >
          💬 Чат клуба
        </button>
      )}

      {/* Качество клуба — единый публичный блок (кольца + подпись возраст/активность), виден всем */}
      {id && <ClubQualityFacts clubId={id} memberCount={club.memberCount} />}

      {/* Правила (опционально) */}
      {club.rules && (
        <>
          <div className="rd-section-sub-h">Правила</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.rules}</div>
          </div>
        </>
      )}

      {/* Участник без доступа: frozen (вступил, ждёт подтверждения первого взноса) или expired
          (подписка истекла — должник по продлению). Один claim-флоу, разные тексты. */}
      {!showTabs && (isFrozenMember || isExpiredMember) && (
        <>
          <div className="rd-glass rd-locked">
            <div className="rd-lock-ico"><LockIcon /></div>
            <div className="rd-text">
              {isExpiredMember ? (
                <>
                  <strong>Подписка истекла</strong>
                  Доступ к активностям закрыт. Продлите взнос организатору — и он снова откроет доступ.
                </>
              ) : (
                <>
                  <strong>Вы вступили в клуб</strong>
                  Доступ к активностям откроет организатор после того, как вы передадите ему взнос.
                </>
              )}
            </div>
          </div>

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
          <div className="rd-glass rd-locked">
            <div className="rd-lock-ico"><LockIcon /></div>
            <div className="rd-text">
              <strong>Активности клуба доступны участникам</strong>
              Содержимое клуба открывается после вступления.
            </div>
          </div>

          {/* Чат и клуб — одно целое (club-chat-link): гость видит, что вход в чат
              лежит через вступление (мокап 02-C). */}
          {club.chatLinked && club.chatDoorEnabled && (
            <div className="rd-cl-chip">
              <span aria-hidden="true">💬</span>
              <span>
                У клуба есть чат — вход откроется после {club.accessType === 'closed' ? 'одобрения заявки' : 'вступления'}
              </span>
            </div>
          )}

          {joinError && <div className="rd-error">{joinError}</div>}

          <div className="rd-cta-wrap">
            {renderCta()}
          </div>
        </>
      )}

      {/* Участник / Организатор: табы с учётом роли */}
      {showTabs && id && (
        <>
          <div className="rd-tabs" role="tablist">
            {tabItems.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`rd-tab-link${item.selected ? ' rd-active' : ''}`}
                onClick={() => handleTabClick(item.key)}
              >
                {item.label}
              </button>
            ))}
          </div>

          {activeTab === 'activities' && <ClubActivitiesTab clubId={id} />}
          {/* managementView={isOrganizer}: организатору здесь показываются attention-бакеты
              «Скоро закончится» / «Оплата вступления» (раньше жили в дублирующем табе «Управление»,
              теперь участники только тут). Обычный участник видит плоский список — бакеты за гейтом. */}
          {activeTab === 'members' && (
            <ClubMembersTab clubId={id} isOrganizer={isOrganizer} managementView={isOrganizer} />
          )}
        </>
      )}

      {/* Модалка заявки (флоу гостя для закрытого клуба) */}
      {showApplyModal && (
        <Modal open onOpenChange={(open) => !open && setShowApplyModal(false)}>
          <div style={{ padding: 16 }}>
            <Text weight="2" style={{ fontSize: 18, display: 'block', marginBottom: 16 }}>
              Заявка в клуб
            </Text>
            {club.applicationQuestion && (
              <Section>
                <div style={{ padding: '8px 16px', color: 'var(--tgui--hint_color)', fontSize: 14 }}>
                  {club.applicationQuestion}
                </div>
                <Input
                  placeholder="Ваш ответ"
                  value={answerText}
                  onChange={(e) => setAnswerText(e.target.value)}
                />
              </Section>
            )}
            {joinError && (
              <div style={{ padding: '8px 0', color: 'var(--tgui--destructive_text_color)', fontSize: 14 }}>
                {joinError}
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
              <Button size="m" mode="outline" onClick={() => setShowApplyModal(false)} stretched>Отмена</Button>
              <Button size="m" onClick={handleApply} disabled={joining} stretched>
                {joining ? <Spinner size="s" /> : 'Отправить'}
              </Button>
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
    </div>
  );
};
