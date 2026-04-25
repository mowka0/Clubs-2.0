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

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

const ACCESS_LABELS: Record<string, string> = {
  open: 'Открытый', closed: 'По заявке', private: 'Приватный',
};

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

  // Order matters: isMember wins over myApplication — after successful payment,
  // handleSuccessfulPayment creates the membership; the approved application row
  // remains in DB but isn't UI-visible because isMember takes precedence here.
  const renderJoinButton = () => {
    if (isOrganizer) return <Button size="l" stretched onClick={() => { haptic.impact('light'); navigate(`/clubs/${id}/manage`); }}>&#x2699;&#xFE0F; Управление клубом</Button>;
    if (isMember) return <Button size="l" mode="outline" disabled stretched>Вы участник &#x2713;</Button>;
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
    // Closed club: user already has an application in flight
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
            </div>
          </div>
        </div>
      </Section>

      {/* Info */}
      <Section>
        <Cell subtitle={club.city}>{club.district ? `${club.city}, ${club.district}` : club.city}</Cell>
        <Cell subtitle="Участники">{club.memberCount} / {club.memberLimit}</Cell>
        <Cell subtitle="Подписка">{formatPrice(club.subscriptionPrice)}</Cell>
      </Section>

      {/* Description */}
      <Section header="О клубе">
        <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--text_color)' }}>
          {club.description}
        </div>
      </Section>

      {/* Rules */}
      {club.rules && (
        <Section header="Правила">
          <div style={{ padding: '12px 16px', lineHeight: 1.5, color: 'var(--tgui--hint_color)' }}>
            {club.rules}
          </div>
        </Section>
      )}

      {/* Join error */}
      {joinError && (
        <div style={{ padding: '8px 16px', color: 'var(--tgui--destructive_text_color)' }}>
          {joinError}
        </div>
      )}

      {/* Join button */}
      <Section>
        {renderJoinButton()}
      </Section>

      {/* Apply modal */}
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
