import { FC, useEffect, useState } from 'react';
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
import { useClubsStore } from '../store/useClubsStore';
import { useAuthStore } from '../store/useAuthStore';
import { getClub } from '../api/clubs';
import { joinClub, applyToClub } from '../api/membership';
import type { ClubDetailDto } from '../types/api';
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
  const { myClubs, fetchMyClubs } = useClubsStore();
  const { user } = useAuthStore();

  const [club, setClub] = useState<ClubDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);
  const [showApplyModal, setShowApplyModal] = useState(false);
  const [answerText, setAnswerText] = useState('');
  const [joinSuccess, setJoinSuccess] = useState(false);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    Promise.all([getClub(id), fetchMyClubs()])
      .then(([clubData]) => { setClub(clubData); setLoading(false); })
      .catch((e) => { setError((e as Error).message); setLoading(false); });
  }, [id, fetchMyClubs]);

  const membership = myClubs.find((m) => m.clubId === id);
  const isMember = !!membership && membership.status === 'active';
  const isOrganizer = club?.ownerId === user?.id || membership?.role === 'organizer';

  const handleJoin = async () => {
    if (!id || !club) return;
    setJoining(true);
    setJoinError(null);
    try {
      await joinClub(id);
      await fetchMyClubs();
      setJoinSuccess(true);
    } catch (e) {
      setJoinError((e as Error).message);
    } finally {
      setJoining(false);
    }
  };

  const handleApply = async () => {
    if (!id || !club) return;
    if (club.applicationQuestion && !answerText.trim()) {
      setJoinError('Введите ответ на вопрос');
      return;
    }
    setJoining(true);
    setJoinError(null);
    try {
      await applyToClub(id, answerText.trim());
      setShowApplyModal(false);
      setJoinSuccess(true);
    } catch (e) {
      setJoinError((e as Error).message);
    } finally {
      setJoining(false);
    }
  };

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}><Spinner size="l" /></div>;
  }

  if (error || !club) {
    return <Placeholder header="Ошибка" description={error ?? 'Клуб не найден'} />;
  }

  const renderJoinButton = () => {
    if (isOrganizer) return <Button size="l" stretched onClick={() => navigate(`/clubs/${id}/manage`)}>&#x2699;&#xFE0F; Управление клубом</Button>;
    if (isMember) return <Button size="l" mode="outline" disabled stretched>Вы участник &#x2713;</Button>;
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
        <Button size="l" onClick={() => setShowApplyModal(true)} stretched>
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
