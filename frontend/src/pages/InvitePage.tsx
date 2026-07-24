import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useApplyToClubMutation, useClubByInviteQuery, useJoinByInviteMutation, useMyClubsQuery } from '../queries/clubs';
import { useCompleteOnboardingMutation } from '../queries/profile';
import { useAuthStore } from '../store/useAuthStore';
import { ApiError } from '../api/apiClient';
import { formatPrice } from '../utils/formatters';
import { FoxEmpty } from '../components/feed/FoxEmpty';
import { WelcomeScene, memberCountCaption } from '../components/onboarding/WelcomeScene';
import { Toast } from '../components/Toast';
import foxInviteArt from '../assets/mascot/fox-invite.png';
import foxErrorArt from '../assets/mascot/fox-error.png';

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
  const myClubsQuery = useMyClubsQuery();
  const joinMutation = useJoinByInviteMutation();
  const applyMutation = useApplyToClubMutation();
  const completeOnboarding = useCompleteOnboardingMutation();
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  const [actionError, setActionError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);
  const [applied, setApplied] = useState(false);
  const [answerText, setAnswerText] = useState('');
  const [welcomeError, setWelcomeError] = useState<string | null>(null);

  // Велком-сцена (онбординг, срез 3): инвайт — главная точка входа новичка, карусель ему
  // отложена deep-link'ом (Layout), поэтому продукт рассказывает сцена ПОСЛЕ вступления.
  const isNewbie = !!user && user.onboardedAt == null;

  const club = clubQuery.data;
  const loading = clubQuery.isPending;
  const joining = joinMutation.isPending || applyMutation.isPending;

  // club-invites (кадр G): в полный клуб прямое вступление невозможно — приглашение
  // деградирует в обычную заявку, организатор может расширить клуб из инбокса.
  const isClubFull = !!club && club.memberCount >= club.memberLimit;

  // Приглашение открыл человек, который уже в клубе (active / frozen / expired — место
  // занято): вместо CTA вступления — «Перейти в клуб». Отфильтровать его в нативном
  // пикере Telegram нельзя (пикер не сообщает и не ограничивает выбор), поэтому
  // страхуемся на посадочной; бэкенд повторное вступление и так отбивает (409).
  const myMembership = myClubsQuery.data?.find((m) => m.clubId === club?.id);
  const isAlreadyMember = !!myMembership && ['active', 'frozen', 'expired'].includes(myMembership.status);

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

  // Сбой запроса — не то же, что битая ссылка: на несуществующий/отозванный код
  // бэкенд отвечает 404, а сеть и 5xx — временные проблемы, лечатся повтором.
  const isInviteNotFound = clubQuery.error instanceof ApiError && clubQuery.error.status === 404;

  if (clubQuery.isError && !isInviteNotFound) {
    return (
      <div className="rd-page">
        <FoxEmpty
          art={foxErrorArt}
          variant="error"
          title="Не удалось открыть приглашение"
          description="Проверь соединение и попробуй ещё раз."
          primary={{ label: 'Повторить', onClick: () => { haptic.impact('light'); clubQuery.refetch(); } }}
        />
      </div>
    );
  }

  // Лендинг приглашения — часто первый экран новичка в приложении: тупик с битой
  // ссылкой обязан давать выход в каталог, иначе человек просто закроет Mini App.
  const invalidInviteScene = (
    <div className="rd-page">
      <FoxEmpty
        art={foxInviteArt}
        title="Ссылка недействительна"
        description="Возможно, приглашение устарело или его отозвали — попроси друга прислать новую ссылку"
        primary={{ label: 'Найти клубы', onClick: () => navigate('/') }}
      />
    </div>
  );

  // Сюда доходят 404 (код не существует или отозван) и успешный ответ с пустым
  // телом — без фолбэка страница осталась бы белым экраном.
  if (!club) return invalidInviteScene;

  // Велком-CTA «Перейти в клуб»: порядок ЖЁСТКИЙ — ответ сервера → навигация → setUser
  // (ловушка среза 1: профиль в сторе = гейт Layout; см. useCompleteOnboardingMutation).
  // Здесь гейт закрыт startParam'ом, но порядок сохраняем — он единственный корректный везде.
  const handleWelcomeCta = async () => {
    if (completeOnboarding.isPending) return;
    haptic.impact('medium');
    try {
      const freshUser = await completeOnboarding.mutateAsync('MEMBER');
      navigate(`/clubs/${club.id}`);
      setUser(freshUser);
    } catch {
      haptic.notify('error');
      setWelcomeError('Не удалось продолжить. Проверьте связь и попробуйте ещё раз.');
    }
  };

  if (joined) {
    const isPaid = club.subscriptionPrice > 0;
    // Новичок: вместо сухого «Добро пожаловать» — велком-сцена (кадр A/B). CTA помечает
    // онбординг дверью MEMBER — карусель с дверями такому человеку больше не показывается.
    if (isNewbie) {
      return (
        <>
          <WelcomeScene
            variant={isPaid ? 'paid' : 'free'}
            clubName={club.name}
            clubCaption={`${club.city} · ${isPaid ? formatPrice(club.subscriptionPrice) : memberCountCaption(club.memberCount)}`}
            clubAvatarUrl={club.avatarUrl}
            ctaPending={completeOnboarding.isPending}
            onCta={handleWelcomeCta}
          />
          {welcomeError && <Toast message={welcomeError} onClose={() => setWelcomeError(null)} />}
        </>
      );
    }
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
    // Новичок остался БЕЗ клуба (мест не было, ушла заявка) — кадр C: мини-рассказ о продукте
    // + «Посмотреть другие клубы». Онбординг НЕ помечаем: при следующем обычном входе без
    // клуба ему честно показать карусель с дверями.
    if (isNewbie) {
      return (
        <WelcomeScene
          variant="applied"
          clubName={club.name}
          clubCaption={`${club.city} · мест пока нет`}
          clubAvatarUrl={club.avatarUrl}
          ctaPending={false}
          onCta={() => { haptic.impact('light'); navigate('/', { replace: true }); }}
        />
      );
    }
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

      {!isAlreadyMember && isClubFull && (
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
      {!isAlreadyMember && isClubFull && club.applicationQuestion && (
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
        {isAlreadyMember ? (
          <>
            <div className="rd-cl-chip">
              <span aria-hidden="true">✓</span>
              <span>Вы уже состоите в этом клубе</span>
            </div>
            <button
              type="button"
              className="rd-btn-primary"
              onClick={() => { haptic.impact('light'); navigate(`/clubs/${club.id}`, { replace: true }); }}
            >
              Перейти в клуб
            </button>
          </>
        ) : isClubFull ? (
          <button type="button" className="rd-btn-primary" onClick={handleApply} disabled={joining}>
            {joining ? <Spinner size="s" /> : 'Попроситься в клуб'}
          </button>
        ) : (
          <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
            {joining ? <Spinner size="s" /> : 'Вступить в клуб'}
          </button>
        )}
        {!isAlreadyMember && club.ownerFirstName && (
          <div className="rd-cta-hint">
            Приглашение от {club.ownerFirstName}{club.ownerLastName ? ` ${club.ownerLastName}` : ''}
          </div>
        )}
      </div>
    </div>
  );
};
