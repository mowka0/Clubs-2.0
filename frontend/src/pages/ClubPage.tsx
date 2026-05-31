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
import { BrandBackdrop } from '../components/BrandBackdrop';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

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

function getClubInitials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

const LockIcon: FC = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="11" width="16" height="11" rx="2.5" />
    <path d="M8 11V7a4 4 0 1 1 8 0v4" />
  </svg>
);

const ClosedChipIcon: FC = () => (
  <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" aria-hidden="true">
    <rect x="5" y="11" width="14" height="10" rx="2" />
    <path d="M8 11V8a4 4 0 1 1 8 0v3" />
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

  const [joinError, setJoinError] = useState<string | null>(null);
  const [showApplyModal, setShowApplyModal] = useState(false);
  const [answerText, setAnswerText] = useState('');
  const [joinSuccess, setJoinSuccess] = useState(false);
  const [pendingPayment, setPendingPayment] = useState<{ priceStars: number; message: string } | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('activities');

  const club = clubQuery.data;
  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];

  const membership = myClubs.find((m) => m.clubId === id);
  const isMember = !!membership && membership.status === 'active';
  const isOrganizer = club?.ownerId === user?.id || membership?.role === 'organizer';
  const myApplication = applications.find((a) => a.clubId === id) ?? null;

  const joining = joinMutation.isPending || applyMutation.isPending || completeFreeMutation.isPending;

  if (clubQuery.isPending) {
    return (
      <div className="brand-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (clubQuery.error || !club) {
    return (
      <div className="brand-page">
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
          <button type="button" className="cp-cta outline" disabled>
            Ожидаем оплату — {pendingPayment.priceStars} Stars
          </button>
          <div className="cp-cta-hint">{pendingPayment.message}</div>
        </>
      );
    }
    if (myApplication?.status === 'pending') {
      return (
        <button type="button" className="cp-cta outline" disabled>
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
              className="cp-cta"
              onClick={handleCompleteFreeMembership}
              disabled={joining}
            >
              {joining ? <Spinner size="s" /> : 'Завершить вступление'}
            </button>
            <div className="cp-cta-hint">
              Заявка одобрена. Нажмите чтобы вступить.
            </div>
          </>
        );
      }
      return (
        <>
          <button type="button" className="cp-cta outline" disabled>
            Ожидаем оплату — {price} Stars
          </button>
          <div className="cp-cta-hint">
            Заявка одобрена. Счёт отправлен в Telegram — оплатите его, чтобы вступить.
          </div>
        </>
      );
    }
    if (joinSuccess) {
      return (
        <button type="button" className="cp-cta outline" disabled>
          Заявка отправлена
        </button>
      );
    }
    if (club.accessType === 'open') {
      return (
        <button type="button" className="cp-cta" onClick={handleJoin} disabled={joining}>
          {joining ? <Spinner size="s" /> : 'Вступить'}
        </button>
      );
    }
    if (club.accessType === 'closed') {
      return (
        <>
          <button
            type="button"
            className="cp-cta"
            onClick={() => { haptic.impact('light'); setShowApplyModal(true); }}
          >
            Хочу вступить
          </button>
          <div className="cp-cta-hint">
            Организатор задаст один вопрос. Ответ увидит только он.
          </div>
        </>
      );
    }
    return null;
  };

  const showTabs = isMember || isOrganizer;
  const roleBadgeLabel = isOrganizer ? 'Вы организатор' : isMember ? 'Вы участник' : null;

  const tabItems: TabItem[] = [
    { key: 'activities', label: 'Активности', selected: activeTab === 'activities' },
    { key: 'members', label: 'Участники', selected: activeTab === 'members' },
  ];
  if (isOrganizer) {
    tabItems.push({ key: 'manage', label: 'Управление', selected: false });
  }

  const capacityPct = club.memberLimit > 0
    ? Math.min(100, Math.round((club.memberCount / club.memberLimit) * 100))
    : 0;
  const locationLine = club.district ?? null;
  const isPaid = club.subscriptionPrice > 0;

  return (
    <div className="brand-page">
      <BrandBackdrop />

      {/* Header / Cover */}
      <div className="cp-cover">
        <div
          className={`cp-avt${showTabs ? ' featured' : ''}`}
          data-cat={club.category}
        >
          {club.avatarUrl
            ? <img src={club.avatarUrl} alt="" />
            : getClubInitials(club.name)}
        </div>
        <div className="body">
          <h1 className="name">{club.name}</h1>
          <div className="cp-chips">
            <span className="cp-chip">{CATEGORY_LABELS[club.category] ?? club.category}</span>
            <span className={`cp-chip${club.accessType === 'closed' ? ' closed' : ''}`}>
              {club.accessType === 'closed' && <ClosedChipIcon />}
              {ACCESS_LABELS[club.accessType] ?? club.accessType}
            </span>
            {roleBadgeLabel && <span className="cp-chip role">{roleBadgeLabel}</span>}
          </div>
        </div>
      </div>

      {/* Stats row */}
      <div className="cp-stats">
        <div className="cp-stat">
          <span className="label">Участники</span>
          <span className="value">{club.memberCount} / {club.memberLimit}</span>
          <div className="capbar"><div className="fill" style={{ width: `${capacityPct}%` }} /></div>
        </div>
        <div className="cp-stat">
          <span className="label">Подписка</span>
          <span className="value" style={isPaid ? { fontSize: 14.5, letterSpacing: '-0.008em' } : undefined}>
            {formatPrice(club.subscriptionPrice)}
          </span>
        </div>
        <div className="cp-stat">
          <span className="label">Где</span>
          <span className="value" style={{ fontSize: 15 }}>{club.city}</span>
          {locationLine && <span className="sub">{locationLine}</span>}
        </div>
      </div>

      {/* About */}
      <div className="cp-card">
        <span className="label">О клубе</span>
        <div className="body-text">{club.description}</div>
      </div>

      {/* Rules (optional) */}
      {club.rules && (
        <div className="cp-card rules">
          <span className="label">Правила</span>
          <div className="body-text">{club.rules}</div>
        </div>
      )}

      {/* Visitor: lock placeholder + CTA */}
      {!showTabs && (
        <>
          <div className="cp-locked">
            <div className="ico"><LockIcon /></div>
            <div className="text">
              <strong>Активности клуба доступны участникам</strong>
              Содержимое клуба открывается после вступления.
            </div>
          </div>

          {joinError && (
            <div style={{ padding: '0 20px 8px', color: '#FF8B8B', fontSize: 13, textAlign: 'center' }}>
              {joinError}
            </div>
          )}

          <div className="cp-cta-wrap">
            {renderCta()}
          </div>
        </>
      )}

      {/* Member / Organizer: role-aware tabs */}
      {showTabs && id && (
        <>
          <div className="cp-tab-row" role="tablist">
            {tabItems.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`cp-tab${item.selected ? ' active' : ''}${item.key === 'manage' ? ' manage' : ''}`}
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
    </div>
  );
};
