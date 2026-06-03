import { FC, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useClubQuery } from '../queries/clubs';
import {
  useCastVoteMutation,
  useConfirmParticipationMutation,
  useDeclineParticipationMutation,
  useEventQuery,
  useMyVoteQuery,
} from '../queries/events';

function getInitials(name: string): string {
  return name.replace(/[«»"']/g, '').split(/\s+/).filter(Boolean).slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase()).join('');
}

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
  const navigate = useNavigate();
  const haptic = useHaptic();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  const eventQuery = useEventQuery(isAuthenticated ? id : undefined);
  const myVoteQuery = useMyVoteQuery(isAuthenticated ? id : undefined);
  const hostClubQuery = useClubQuery(eventQuery.data?.clubId);

  const castVoteMutation = useCastVoteMutation();
  const confirmMutation = useConfirmParticipationMutation();
  const declineMutation = useDeclineParticipationMutation();

  const [actionError, setActionError] = useState<string | null>(null);

  const event = eventQuery.data;
  const myVote = myVoteQuery.data?.vote ?? null;
  const loading = eventQuery.isPending || myVoteQuery.isPending;
  const voting =
    castVoteMutation.isPending || confirmMutation.isPending || declineMutation.isPending;
  const loadError = eventQuery.error?.message;

  const handleVote = (vote: 'going' | 'maybe' | 'not_going') => {
    if (!id || voting) return;
    haptic.select();
    setActionError(null);
    castVoteMutation.mutate(
      { eventId: id, vote },
      {
        onSuccess: () => haptic.notify('success'),
        onError: (e) => {
          setActionError(e.message);
          haptic.notify('error');
        },
      },
    );
  };

  const handleConfirm = () => {
    if (!id || voting) return;
    haptic.impact('medium');
    setActionError(null);
    confirmMutation.mutate(id, {
      onSuccess: () => haptic.notify('success'),
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  const handleDecline = () => {
    if (!id || voting) return;
    haptic.impact('medium');
    setActionError(null);
    declineMutation.mutate(id, {
      onSuccess: () => haptic.notify('warning'),
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  if (loading) {
    return (
      <div className="rd-page" style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
        <Spinner size="l" />
      </div>
    );
  }

  if (loadError || !event) {
    return (
      <div className="rd-page">
        <Placeholder header="Ошибка" description={loadError ?? 'Событие не найдено'} />
      </div>
    );
  }

  const fillPercent = event.participantLimit > 0
    ? Math.min((event.goingCount / event.participantLimit) * 100, 100)
    : 0;

  // Backend (`VoteService.castVote`) принимает голос ТОЛЬКО при status='upcoming'.
  const showVoting = event.status === 'upcoming';
  const showStage2 = event.status === 'stage_2';
  const showConfirmed =
    event.confirmedCount > 0 &&
    (event.status === 'stage_2' || event.status === 'completed');

  return (
    <div className="rd-page">
      {/* Hero */}
      <div className="rd-hero rd-compact">
        <div className="rd-hero-bg" data-cat="sport" />
        <div className="rd-hero-meta">
          <div className="rd-hero-type-badge">СОБЫТИЕ</div>
          <div className="rd-hero-ttl">{event.title}</div>
          <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
            {formatEventDate(event.eventDatetime)}
          </div>
        </div>
      </div>

      {/* Host club */}
      {hostClubQuery.data && (
        <button
          type="button"
          className="rd-glass"
          style={{ display: 'block', width: '100%', textAlign: 'left', padding: 0, marginBottom: 14, cursor: 'pointer' }}
          onClick={() => { haptic.impact('light'); navigate(`/clubs/${event.clubId}`); }}
        >
          <div className="rd-host-row">
            <span className="rd-ico">
              {hostClubQuery.data.avatarUrl
                ? <img src={hostClubQuery.data.avatarUrl} alt="" />
                : getInitials(hostClubQuery.data.name)}
            </span>
            <div className="rd-info">
              <div className="rd-ttl">{hostClubQuery.data.name}</div>
              <div className="rd-met">организатор</div>
            </div>
          </div>
        </button>
      )}

      {/* Location */}
      {event.locationText && (
        <div className="rd-glass" style={{ marginBottom: 14, overflow: 'hidden' }}>
          <div className="rd-mini-map" />
          <div className="rd-addr-body">
            <div className="rd-a-ttl">{event.locationText}</div>
          </div>
        </div>
      )}

      {/* Description */}
      {event.description && (
        <>
          <div className="rd-section-sub-h">Описание</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{event.description}</div>
          </div>
        </>
      )}

      {/* Recruitment stats */}
      <div className="rd-section-sub-h">Набор · {event.goingCount} / {event.participantLimit}</div>
      <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
        <div className="rd-progress">
          <div className="rd-fill" style={{ width: `${fillPercent}%` }} />
        </div>
        <div className="rd-stat-line">
          <span>Пойдут: {event.goingCount}</span>
          <span>Возможно: {event.maybeCount}</span>
          <span>Нет: {event.notGoingCount}</span>
        </div>
      </div>

      {/* Voting */}
      {showVoting && (
        <>
          <div className="rd-section-sub-h">Голосование</div>
          {myVote && (
            <div style={{ marginBottom: 10 }}>
              <span className="rd-badge rd-going">Ваш голос: {VOTE_LABELS[myVote] ?? myVote}</span>
            </div>
          )}
          {actionError && <div className="rd-error">{actionError}</div>}
          <div className="rd-vote-row" style={{ marginBottom: 18 }}>
            <button type="button" className={`rd-vote-btn${myVote === 'going' ? ' rd-active' : ''}`} onClick={() => handleVote('going')} disabled={voting}>
              Пойду
            </button>
            <button type="button" className={`rd-vote-btn${myVote === 'maybe' ? ' rd-active' : ''}`} onClick={() => handleVote('maybe')} disabled={voting}>
              Возможно
            </button>
            <button type="button" className={`rd-vote-btn${myVote === 'not_going' ? ' rd-active' : ''}`} onClick={() => handleVote('not_going')} disabled={voting}>
              Не пойду
            </button>
          </div>
        </>
      )}

      {/* Stage 2 — confirmation */}
      {showStage2 && (
        <>
          <div className="rd-section-sub-h">Подтверждение участия</div>
          <div style={{ marginBottom: 10 }}>
            {myVote === 'confirmed' && <span className="rd-badge rd-going">Подтверждён</span>}
            {myVote === 'waitlisted' && <span className="rd-badge rd-warn">Лист ожидания</span>}
            {myVote === 'declined' && <span className="rd-badge rd-decline">Отказался</span>}
            {myVote && !['confirmed', 'waitlisted', 'declined'].includes(myVote) && (
              <span className="rd-badge rd-warn">Ваш статус: {VOTE_LABELS[myVote] ?? myVote}</span>
            )}
          </div>
          {actionError && <div className="rd-error">{actionError}</div>}
          {(myVote === 'going' || myVote === 'maybe') && (
            <div className="rd-cta-wrap">
              <button type="button" className="rd-btn-primary" onClick={handleConfirm} disabled={voting}>
                {voting ? <Spinner size="s" /> : 'Подтвердить участие'}
              </button>
              <button type="button" className="rd-btn-outline" style={{ marginTop: 8 }} onClick={handleDecline} disabled={voting}>
                Отказаться
              </button>
            </div>
          )}
        </>
      )}

      {/* Confirmed participants */}
      {showConfirmed && (
        <>
          <div className="rd-section-sub-h">Участники</div>
          <div className="rd-glass">
            <div className="rd-kv">
              Подтверждено
              <span className="rd-v">{event.confirmedCount} / {event.participantLimit}</span>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
