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
import {
  useApplyToClubMutation,
  useClubQuery,
  useJoinClubMutation,
  useLeaveClubMutation,
  useMyClubsQuery,
} from '../queries/clubs';
import {
  useCompleteFreeMembershipMutation,
  useMyApplicationsQuery,
} from '../queries/applications';
import { ApiError } from '../api/apiClient';
import { isPendingPayment } from '../types/api';
import { formatPrice } from '../utils/formatters';
import { ClubActivitiesTab } from '../components/club/ClubActivitiesTab';
import { ClubMembersTab } from '../components/club/ClubMembersTab';
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
  const [joinSuccess, setJoinSuccess] = useState(false);
  const [pendingPayment, setPendingPayment] = useState<{ priceStars: number; message: string } | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('activities');
  const [showLeaveModal, setShowLeaveModal] = useState(false);
  const [leaveError, setLeaveError] = useState<string | null>(null);

  const club = clubQuery.data;
  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];

  const membership = myClubs.find((m) => m.clubId === id);
  const isOwner = !!club && club.ownerId === user?.id;
  const isOrganizer = isOwner || membership?.role === 'organizer';
  // Active membership = full member; cancelled paid membership inside its
  // paid period = "still inside the club" — tabs stay visible, but instead
  // of «Выйти из клуба» the footer shows a read-only «Подписка отменена» note.
  const isActiveMember = !!membership && membership.status === 'active';
  const isCancelledInPeriod =
    !!membership
    && membership.status === 'cancelled'
    && !!membership.subscriptionExpiresAt
    && new Date(membership.subscriptionExpiresAt).getTime() > Date.now();
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
      onSuccess: (result) => {
        if (isPendingPayment(result)) {
          setPendingPayment({ priceStars: result.priceStars, message: result.message });
        } else {
          setJoinSuccess(true);
        }
        haptic.notify('success');
      },
      onError: (e) => {
        // 409 — silent recovery: cache already invalidated, UI was stale.
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

  // Recovery handler for the legacy stuck state: a free-club application was
  // approved but the auto-create membership branch never ran (earlier bug /
  // pre-existing data). Backend `complete-free-membership` re-creates the
  // membership idempotently; on success the page re-renders with member tabs.
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
    const isPaidLeave = club.subscriptionPrice > 0;
    leaveMutation.mutate(id, {
      onSuccess: () => {
        haptic.notify('success');
        setShowLeaveModal(false);
        if (isPaidLeave) {
          // Paid leave keeps the membership row (status=cancelled,
          // subscription_expires_at in the future) — stay on the club so the
          // cancelled-banner takes over from the leave icon.
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

  // Tab «Управление» is a navigate-link, not a state-toggle: tap fires haptic
  // impact (not select) and routes to /manage. activeTab never holds 'manage'.
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
    if (pendingPayment) {
      return (
        <>
          <button type="button" className="rd-btn-outline" disabled>
            Ожидаем оплату — {pendingPayment.priceStars} Stars
          </button>
          <div className="rd-cta-hint">{pendingPayment.message}</div>
        </>
      );
    }
    if (myApplication?.status === 'pending') {
      return (
        <button type="button" className="rd-btn-outline" disabled>
          Заявка на рассмотрении
        </button>
      );
    }
    if (myApplication?.status === 'approved') {
      const price = club.subscriptionPrice ?? 0;
      if (price <= 0) {
        // Legacy stuck state: free club, approved, but membership row missing.
        // Surface a recovery CTA instead of the misleading «Ожидаем оплату».
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
      return (
        <>
          <button type="button" className="rd-btn-outline" disabled>
            Ожидаем оплату — {price} Stars
          </button>
          <div className="rd-cta-hint">
            Заявка одобрена. Счёт отправлен в Telegram — оплатите его, чтобы вступить.
          </div>
        </>
      );
    }
    if (joinSuccess) {
      return (
        <button type="button" className="rd-btn-outline" disabled>
          Заявка отправлена
        </button>
      );
    }
    if (club.accessType === 'open') {
      return (
        <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
          {joining ? <Spinner size="s" /> : 'Вступить'}
        </button>
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

  const leaveVariant: 'free' | 'paid' = club.subscriptionPrice > 0 ? 'paid' : 'free';
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
      {/* Hero cover */}
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

      {/* About */}
      <div className="rd-section-sub-h">О клубе</div>
      <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
        <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.description}</div>
      </div>

      {/* Rules (optional) */}
      {club.rules && (
        <>
          <div className="rd-section-sub-h">Правила</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.rules}</div>
          </div>
        </>
      )}

      {/* Visitor: lock placeholder + CTA */}
      {!showTabs && (
        <>
          <div className="rd-glass rd-locked">
            <div className="rd-lock-ico"><LockIcon /></div>
            <div className="rd-text">
              <strong>Активности клуба доступны участникам</strong>
              Содержимое клуба открывается после вступления.
            </div>
          </div>

          {joinError && <div className="rd-error">{joinError}</div>}

          <div className="rd-cta-wrap">
            {renderCta()}
          </div>
        </>
      )}

      {/* Member / Organizer: role-aware tabs */}
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
          {activeTab === 'members' && <ClubMembersTab clubId={id} isOrganizer={isOrganizer} />}
        </>
      )}

      {/* Apply modal (visitor closed-club flow) */}
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
