import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useClubByInviteQuery, useJoinByInviteMutation } from '../queries/clubs';

const CATEGORY_LABELS: Record<string, string> = {
  sport: 'Спорт', creative: 'Творчество', food: 'Еда',
  board_games: 'Настолки', cinema: 'Кино', education: 'Образование',
  travel: 'Путешествия', other: 'Другое',
};

function getClubInitials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

export const InvitePage: FC = () => {
  useBackButton(true);
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const clubQuery = useClubByInviteQuery(code);
  const joinMutation = useJoinByInviteMutation();

  const [actionError, setActionError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);

  const club = clubQuery.data;
  const loading = clubQuery.isPending;
  const loadError = clubQuery.error?.message;
  const joining = joinMutation.isPending;

  const handleJoin = () => {
    if (!code) return;
    haptic.impact('medium');
    setActionError(null);
    joinMutation.mutate(code, {
      onSuccess: () => {
        setJoined(true);
        haptic.notify('success');
      },
      onError: (e) => {
        setActionError(e.message);
        haptic.notify('error');
      },
    });
  };

  if (loading) {
    return (
      <div className="rd-page">
        <div className="rd-spinner-row" style={{ paddingTop: 60 }}>
          <Spinner size="l" />
        </div>
      </div>
    );
  }

  if (loadError && !club) {
    return (
      <div className="rd-page">
        <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
          <div className="rd-title">Ссылка недействительна</div>
          <div className="rd-sub">Эта ссылка-приглашение устарела или не существует</div>
        </div>
      </div>
    );
  }

  if (!club) return null;

  if (joined) {
    return (
      <div className="rd-page">
        <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
          <div className="rd-title">Добро пожаловать!</div>
          <div className="rd-sub">Вы вступили в клуб «{club.name}»</div>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={() => { haptic.impact('light'); navigate(`/clubs/${club.id}`); }}
            style={{ maxWidth: 240, margin: '0 auto' }}
          >
            Перейти в клуб
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="rd-page">
      <div className="rd-ft-eyebrow">Приглашение в клуб</div>

      <div style={{ display: 'flex', gap: 14, alignItems: 'center', margin: '6px 0 18px' }}>
        <span className="rd-avatar" style={{ width: 64, height: 64, borderRadius: 16, fontSize: 24 }}>
          {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : getClubInitials(club.name)}
        </span>
        <div style={{ minWidth: 0 }}>
          <div className="rd-page-h" style={{ fontSize: 22 }}>{club.name}</div>
          <div style={{ marginTop: 6 }}>
            <span className="rd-badge rd-neutral2">{CATEGORY_LABELS[club.category] ?? club.category}</span>
          </div>
        </div>
      </div>

      <div className="rd-glass rd-rep-panel" style={{ marginBottom: 14 }}>
        <div className="rd-kv"><span>Город</span><span className="rd-v">{club.city}</span></div>
        <div className="rd-kv"><span>Участники</span><span className="rd-v">{club.memberCount} / {club.memberLimit}</span></div>
        {club.subscriptionPrice > 0 && (
          <div className="rd-kv"><span>Подписка</span><span className="rd-v">{club.subscriptionPrice} Stars / мес</span></div>
        )}
      </div>

      {club.description && (
        <>
          <div className="rd-section-sub-h">О клубе</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.description}</div>
          </div>
        </>
      )}

      {actionError && <div className="rd-error">{actionError}</div>}

      <div className="rd-cta-wrap">
        <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
          {joining ? <Spinner size="s" /> : 'Вступить в клуб'}
        </button>
      </div>
    </div>
  );
};
