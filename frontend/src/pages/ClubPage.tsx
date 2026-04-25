import { FC, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  List,
  Section,
  Cell,
  Button,
  Spinner,
  Placeholder,
  Input,
  Modal,
  Text,
  Badge,
  TabsList,
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
import { useMyApplicationsQuery } from '../queries/applications';
import { ApiError } from '../api/apiClient';
import { isPendingPayment } from '../types/api';
import { formatPrice } from '../utils/formatters';
import { ClubEventsTab } from '../components/club/ClubEventsTab';
import { ClubMembersTab } from '../components/club/ClubMembersTab';
import { ClubProfileTab } from '../components/club/ClubProfileTab';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

const ACCESS_LABELS: Record<string, string> = {
  open: 'Открытый', closed: 'По заявке', private: 'Приватный',
};

type TabId = 'events' | 'members' | 'profile';
type TabKey = TabId | 'manage';

interface TabItem {
  key: TabKey;
  label: string;
  selected: boolean;
}

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

  const [joinError, setJoinError] = useState<string | null>(null);
  const [showApplyModal, setShowApplyModal] = useState(false);
  const [answerText, setAnswerText] = useState('');
  const [joinSuccess, setJoinSuccess] = useState(false);
  const [pendingPayment, setPendingPayment] = useState<{ priceStars: number; message: string } | null>(null);
  const [activeTab, setActiveTab] = useState<TabId>('events');

  const club = clubQuery.data;
  const myClubs = myClubsQuery.data ?? [];
  const applications = applicationsQuery.data ?? [];

  const membership = myClubs.find((m) => m.clubId === id);
  const isMember = !!membership && membership.status === 'active';
  const isOrganizer = club?.ownerId === user?.id || membership?.role === 'organizer';
  const myApplication = applications.find((a) => a.clubId === id) ?? null;

  const joining = joinMutation.isPending || applyMutation.isPending;

  const loading = clubQuery.isPending;
  const error = clubQuery.error?.message;

  const handleJoin = () => {
    if (!id || !club) return;
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
        // On 409 the UI state is stale (another tab approved an app, payment completed elsewhere).
        // The mutation already invalidated club + my clubs caches; treat as silent recovery.
        if (e instanceof ApiError && e.status === 409) {
          return;
        }
        setJoinError(e.message);
        haptic.notify('error');
      },
    });
  };

  const handleApply = () => {
    if (!id || !club) return;
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

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}><Spinner size="l" /></div>;
  }

  if (error || !club) {
    return <Placeholder header="Ошибка" description={error ?? 'Клуб не найден'} />;
  }

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

  // Visitor CTA: order matters — isMember/isOrganizer never reach here
  // (they get role-aware tabs instead, status shown as header badge).
  const renderJoinButton = () => {
    if (pendingPayment) {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Button size="l" mode="outline" disabled stretched>
            &#x1F4B3; Ожидаем оплату &#x2013; {pendingPayment.priceStars} Stars
          </Button>
          <Text style={{ fontSize: 13, color: 'var(--tgui--hint_color)', textAlign: 'center' }}>
            {pendingPayment.message}
          </Text>
        </div>
      );
    }
    if (myApplication?.status === 'pending') {
      return <Button size="l" mode="outline" disabled stretched>&#x23F3; Заявка на рассмотрении</Button>;
    }
    if (myApplication?.status === 'approved') {
      const price = club.subscriptionPrice ?? 0;
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Button size="l" mode="outline" disabled stretched>
            &#x1F4B3; Ожидаем оплату{price > 0 ? ` – ${price} Stars` : ''}
          </Button>
          <Text style={{ fontSize: 13, color: 'var(--tgui--hint_color)', textAlign: 'center' }}>
            Заявка одобрена. Счёт отправлен в Telegram — оплатите его, чтобы вступить.
          </Text>
        </div>
      );
    }
    if (joinSuccess) return <Button size="l" mode="outline" disabled stretched>Заявка отправлена &#x2713;</Button>;

    if (club.accessType === 'open') {
      return (
        <Button size="l" onClick={handleJoin} disabled={joining} stretched>
          {joining ? <Spinner size="s" /> : 'Вступить'}
        </Button>
      );
    }
    if (club.accessType === 'closed') {
      return (
        <Button size="l" onClick={() => { haptic.impact('light'); setShowApplyModal(true); }} stretched>
          Хочу вступить
        </Button>
      );
    }
    return null;
  };

  const showTabs = isMember || isOrganizer;
  const roleBadgeLabel = isOrganizer ? 'Вы организатор' : isMember ? 'Вы участник' : null;

  // Build TabsList children as a homogeneous array — telegram-ui v2 typings
  // require ReactElement<TabsItemProps>[], not (Element | false)[].
  const tabItems: TabItem[] = [
    { key: 'events', label: 'События', selected: activeTab === 'events' },
    { key: 'members', label: 'Участники', selected: activeTab === 'members' },
    { key: 'profile', label: 'Мой профиль', selected: activeTab === 'profile' },
  ];
  if (isOrganizer) {
    tabItems.push({ key: 'manage', label: 'Управление', selected: false });
  }

  return (
    <List>
      {/* Header */}
      <Section>
        <div style={{ padding: 16, display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          {club.avatarUrl ? (
            <img src={club.avatarUrl} alt="" style={{ width: 80, height: 80, borderRadius: 16, objectFit: 'cover', flexShrink: 0 }} />
          ) : (
            <div style={{ width: 80, height: 80, borderRadius: 16, background: 'var(--tgui--secondary_bg_color)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 40, flexShrink: 0 }}>&#x1F3E0;</div>
          )}
          <div>
            <Text weight="1" style={{ fontSize: 20, display: 'block' }}>{club.name}</Text>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4 }}>
              <Badge type="number" style={{ fontSize: 11 }}>{CATEGORY_LABELS[club.category] ?? club.category}</Badge>
              <Badge type="number" style={{ fontSize: 11 }}>{ACCESS_LABELS[club.accessType] ?? club.accessType}</Badge>
              {roleBadgeLabel && (
                <Badge type="number" mode="primary" style={{ fontSize: 11 }}>{roleBadgeLabel}</Badge>
              )}
            </div>
          </div>
        </div>
      </Section>

      {/* About */}
      <Section>
        <Cell subtitle={club.city}>{club.district ? `${club.city}, ${club.district}` : club.city}</Cell>
        <Cell subtitle="Участники">{club.memberCount} / {club.memberLimit}</Cell>
        <Cell subtitle="Подписка">{formatPrice(club.subscriptionPrice)}</Cell>
      </Section>

      <Section header="О клубе">
        <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--text_color)' }}>
          {club.description}
        </div>
      </Section>

      {club.rules && (
        <Section header="Правила">
          <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--hint_color)' }}>
            {club.rules}
          </div>
        </Section>
      )}

      {/* Visitor: placeholder + CTA */}
      {!showTabs && (
        <>
          <Section>
            <Placeholder description="События доступны участникам клуба" />
          </Section>

          {joinError && (
            <div style={{ padding: '8px 16px', color: 'var(--tgui--destructive_text_color)' }}>
              {joinError}
            </div>
          )}

          <Section>
            {renderJoinButton()}
          </Section>
        </>
      )}

      {/* Member / Organizer: role-aware tabs */}
      {showTabs && id && (
        <>
          <div style={{ padding: '0 16px 8px' }}>
            <TabsList>
              {tabItems.map((item) => (
                <TabsList.Item
                  key={item.key}
                  selected={item.selected}
                  onClick={() => handleTabClick(item.key)}
                >
                  {item.label}
                </TabsList.Item>
              ))}
            </TabsList>
          </div>

          {activeTab === 'events' && <ClubEventsTab clubId={id} />}
          {activeTab === 'members' && <ClubMembersTab clubId={id} />}
          {activeTab === 'profile' && user?.id && (
            <ClubProfileTab clubId={id} userId={user.id} />
          )}
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
    </List>
  );
};
