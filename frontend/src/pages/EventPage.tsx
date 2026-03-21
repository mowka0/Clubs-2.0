import { FC, useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  List,
  Section,
  Cell,
  Spinner,
  Placeholder,
  Text,
  Button,
  Badge,
} from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useAuthStore } from '../store/useAuthStore';
import {
  getEvent,
  castVote,
  getMyVote,
  confirmParticipation,
  declineParticipation,
} from '../api/events';
import type { EventDetailDto } from '../types/api';

const VOTE_LABELS: Record<string, string> = {
  going: 'Пойду',
  maybe: 'Возможно',
  not_going: 'Не пойду',
  confirmed: 'Подтверждён',
  waitlisted: 'Лист ожидания',
  declined: 'Отказался',
};

function formatEventDate(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const EventPage: FC = () => {
  useBackButton(true);

  const { id } = useParams<{ id: string }>();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  const [event, setEvent] = useState<EventDetailDto | null>(null);
  const [myVote, setMyVote] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [voting, setVoting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (!id) return;
    try {
      const [eventData, voteData] = await Promise.all([
        getEvent(id),
        getMyVote(id),
      ]);
      setEvent(eventData);
      setMyVote(voteData.vote);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [id]);

  useEffect(() => {
    if (!isAuthenticated) return;

    let cancelled = false;

    setLoading(true);
    setError(null);

    loadData().finally(() => {
      if (!cancelled) setLoading(false);
    });

    return () => {
      cancelled = true;
    };
  }, [loadData, isAuthenticated]);

  const handleVote = async (vote: 'going' | 'maybe' | 'not_going') => {
    if (!id || voting) return;
    setVoting(true);
    try {
      await castVote(id, vote);
      const [eventData, voteData] = await Promise.all([
        getEvent(id),
        getMyVote(id),
      ]);
      setEvent(eventData);
      setMyVote(voteData.vote);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setVoting(false);
    }
  };

  const handleConfirm = async () => {
    if (!id || voting) return;
    setVoting(true);
    try {
      await confirmParticipation(id);
      const [eventData, voteData] = await Promise.all([
        getEvent(id),
        getMyVote(id),
      ]);
      setEvent(eventData);
      setMyVote(voteData.vote);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setVoting(false);
    }
  };

  const handleDecline = async () => {
    if (!id || voting) return;
    setVoting(true);
    try {
      await declineParticipation(id);
      const [eventData, voteData] = await Promise.all([
        getEvent(id),
        getMyVote(id),
      ]);
      setEvent(eventData);
      setMyVote(voteData.vote);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setVoting(false);
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (error || !event) {
    return (
      <Placeholder
        header="Ошибка"
        description={error ?? 'Событие не найдено'}
      />
    );
  }

  const fillPercent = event.participantLimit > 0
    ? Math.min((event.goingCount / event.participantLimit) * 100, 100)
    : 0;

  const showVoting = event.status === 'upcoming' || event.status === 'stage_1';
  const showStage2 = event.status === 'stage_2';
  const showConfirmed =
    event.confirmedCount > 0 &&
    (event.status === 'stage_2' || event.status === 'completed');

  return (
    <List>
      {/* Event header */}
      <Section>
        <div style={{ padding: '16px 20px 12px' }}>
          <Text weight="1" style={{ fontSize: 22, display: 'block', lineHeight: 1.3 }}>
            {event.title}
          </Text>
          <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={{ color: 'var(--tgui--hint_color)', fontSize: 15 }}>
              {formatEventDate(event.eventDatetime)}
            </span>
            <span style={{ color: 'var(--tgui--hint_color)', fontSize: 15 }}>
              {event.locationText}
            </span>
          </div>
          {event.description && (
            <div style={{ marginTop: 12, lineHeight: 1.5, color: 'var(--tgui--text_color)', fontSize: 15 }}>
              {event.description}
            </div>
          )}
        </div>
      </Section>

      {/* Stats section */}
      <Section header="Статистика набора">
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {event.goingCount}
            </span>
          }
        >
          Хочу пойти
        </Cell>
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {event.maybeCount}
            </span>
          }
        >
          Может быть
        </Cell>
        <Cell
          after={
            <span style={{ color: 'var(--tgui--hint_color)' }}>
              {event.notGoingCount}
            </span>
          }
        >
          Не пойду
        </Cell>

        {/* Progress bar */}
        <div style={{ padding: '8px 20px 16px' }}>
          <div
            style={{
              width: '100%',
              height: 8,
              borderRadius: 4,
              background: 'var(--tgui--secondary_bg_color)',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                width: `${fillPercent}%`,
                height: '100%',
                borderRadius: 4,
                background: 'linear-gradient(90deg, #34C759, #30D158)',
                transition: 'width 0.3s ease',
              }}
            />
          </div>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              marginTop: 6,
              fontSize: 13,
              color: 'var(--tgui--hint_color)',
            }}
          >
            <span>{event.goingCount} / {event.participantLimit}</span>
            <span>Мест: {event.participantLimit}</span>
          </div>
        </div>
      </Section>

      {/* Voting section */}
      {showVoting && (
        <Section header="Голосование">
          {myVote && (
            <div style={{ padding: '8px 20px' }}>
              <Badge type="number" mode="primary">
                Ваш голос: {VOTE_LABELS[myVote] ?? myVote}
              </Badge>
            </div>
          )}
          <div
            style={{
              display: 'flex',
              gap: 8,
              padding: '12px 20px 16px',
            }}
          >
            <Button
              size="m"
              mode={myVote === 'going' ? 'bezeled' : 'plain'}
              onClick={() => handleVote('going')}
              disabled={voting}
              stretched
            >
              Пойду
            </Button>
            <Button
              size="m"
              mode={myVote === 'maybe' ? 'bezeled' : 'plain'}
              onClick={() => handleVote('maybe')}
              disabled={voting}
              stretched
            >
              Возможно
            </Button>
            <Button
              size="m"
              mode={myVote === 'not_going' ? 'bezeled' : 'plain'}
              onClick={() => handleVote('not_going')}
              disabled={voting}
              stretched
            >
              Не пойду
            </Button>
          </div>
        </Section>
      )}

      {/* Stage 2 section */}
      {showStage2 && (
        <Section header="Подтверждение участия">
          {/* Current status badge */}
          <div style={{ padding: '8px 20px' }}>
            {myVote === 'confirmed' && (
              <Badge
                type="number"
                mode="primary"
                style={{ background: '#34C759', color: '#fff' }}
              >
                Подтверждён
              </Badge>
            )}
            {myVote === 'waitlisted' && (
              <Badge
                type="number"
                mode="primary"
                style={{ background: '#FF9500', color: '#fff' }}
              >
                Лист ожидания
              </Badge>
            )}
            {myVote === 'declined' && (
              <Badge
                type="number"
                mode="primary"
                style={{ background: '#FF3B30', color: '#fff' }}
              >
                Отказался
              </Badge>
            )}
            {myVote && !['confirmed', 'waitlisted', 'declined'].includes(myVote) && (
              <Text style={{ color: 'var(--tgui--hint_color)', fontSize: 14 }}>
                Ваш статус: {VOTE_LABELS[myVote] ?? myVote}
              </Text>
            )}
          </div>

          {/* Action buttons for users who voted going/maybe but haven't confirmed yet */}
          {(myVote === 'going' || myVote === 'maybe') && (
            <div
              style={{
                display: 'flex',
                gap: 8,
                padding: '12px 20px 16px',
              }}
            >
              <Button
                size="m"
                mode="bezeled"
                onClick={handleConfirm}
                disabled={voting}
                stretched
              >
                {voting ? <Spinner size="s" /> : 'Подтвердить участие'}
              </Button>
              <Button
                size="m"
                mode="plain"
                onClick={handleDecline}
                disabled={voting}
                stretched
              >
                Отказаться
              </Button>
            </div>
          )}
        </Section>
      )}

      {/* Confirmed participants */}
      {showConfirmed && (
        <Section header="Участники">
          <Cell
            after={
              <span style={{ color: 'var(--tgui--hint_color)' }}>
                {event.confirmedCount} / {event.participantLimit}
              </span>
            }
          >
            Подтверждено
          </Cell>
        </Section>
      )}
    </List>
  );
};
