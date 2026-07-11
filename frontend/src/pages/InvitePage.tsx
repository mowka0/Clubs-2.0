import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useApplyToClubMutation, useClubByInviteQuery, useJoinByInviteMutation } from '../queries/clubs';
import { formatPrice } from '../utils/formatters';

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
  const applyMutation = useApplyToClubMutation();

  const [actionError, setActionError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);
  const [applied, setApplied] = useState(false);
  const [answerText, setAnswerText] = useState('');

  const club = clubQuery.data;
  const loading = clubQuery.isPending;
  const loadError = clubQuery.error?.message;
  const joining = joinMutation.isPending || applyMutation.isPending;

  // club-invites (кадр G): в полный клуб прямое вступление невозможно — приглашение
  // деградирует в обычную заявку, организатор может расширить клуб из инбокса.
  const isClubFull = !!club && club.memberCount >= club.memberLimit;

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

  const handleApply = () => {
    if (!club) return;
    if (club.applicationQuestion && !answerText.trim()) {
      setActionError('Введите ответ на вопрос организатора');
      return;
    }
    haptic.impact('medium');
    setActionError(null);
    applyMutation.mutate(
      { clubId: club.id, answerText: answerText.trim() },
      {
        onSuccess: () => {
          setApplied(true);
          haptic.notify('success');
        },
        onError: (e) => {
          setActionError(e.message);
          haptic.notify('error');
        },
      },
    );
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
    const isPaid = club.subscriptionPrice > 0;
    return (
      <div className="rd-page">
        <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
          <div className="rd-title">Добро пожаловать!</div>
          <div className="rd-sub">
            Вы вступили в клуб «{club.name}»
            {isPaid && '. Доступ к активностям откроет организатор после того, как вы передадите ему взнос.'}
          </div>
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

  if (applied) {
    return (
      <div className="rd-page">
        <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
          <div className="rd-title">Заявка отправлена</div>
          <div className="rd-sub">
            В клубе «{club.name}» сейчас нет мест. Организатор увидит вашу заявку и может
            расширить клуб — мы сообщим о решении.
          </div>
          <button
            type="button"
            className="rd-btn-primary"
            onClick={() => { haptic.impact('light'); navigate('/', { replace: true }); }}
            style={{ maxWidth: 240, margin: '0 auto' }}
          >
            К списку клубов
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
        <div className="rd-kv">
          <span>Участники</span>
          <span className="rd-v">{club.memberCount} / {club.memberLimit}{isClubFull ? ' · мест нет' : ''}</span>
        </div>
        {club.subscriptionPrice > 0 && (
          <div className="rd-kv"><span>Подписка</span><span className="rd-v">{formatPrice(club.subscriptionPrice)}</span></div>
        )}
      </div>

      {isClubFull && (
        <div className="rd-cl-chip">
          <span aria-hidden="true">👥</span>
          <span>В клубе кончились места — вы всё равно можете попроситься, организатор может расширить клуб</span>
        </div>
      )}

      {club.description && (
        <>
          <div className="rd-section-sub-h">О клубе</div>
          <div className="rd-glass" style={{ padding: '14px 16px', marginBottom: 14 }}>
            <div className="rd-body-text" style={{ margin: 0, padding: 0 }}>{club.description}</div>
          </div>
        </>
      )}

      {/* Полный клуб + вопрос организатора: заявка требует ответа — поле в общем стиле форм. */}
      {isClubFull && club.applicationQuestion && (
        <label className="rd-field" style={{ marginBottom: 14 }}>
          <span className="rd-label">{club.applicationQuestion}</span>
          <input
            className="rd-input"
            placeholder="Ваш ответ"
            value={answerText}
            onChange={(e) => setAnswerText(e.target.value)}
          />
        </label>
      )}

      {actionError && <div className="rd-error">{actionError}</div>}

      <div className="rd-cta-wrap">
        {isClubFull ? (
          <button type="button" className="rd-btn-primary" onClick={handleApply} disabled={joining}>
            {joining ? <Spinner size="s" /> : 'Попроситься в клуб'}
          </button>
        ) : (
          <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
            {joining ? <Spinner size="s" /> : 'Вступить в клуб'}
          </button>
        )}
        {club.ownerFirstName && (
          <div className="rd-cta-hint">
            Приглашение от {club.ownerFirstName}{club.ownerLastName ? ` ${club.ownerLastName}` : ''}
          </div>
        )}
      </div>
    </div>
  );
};
