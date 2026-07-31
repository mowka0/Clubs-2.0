import { FC, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import {
  useApplyToClubMutation,
  useClubByInviteQuery,
  useClubQuery,
  useJoinByInviteMutation,
  useMyClubsQuery,
} from '../queries/clubs';
import { useClubQualityQuery } from '../queries/clubQuality';
import { useCompleteTourMutation } from '../queries/profile';
import { useAuthStore } from '../store/useAuthStore';
import { ApiError } from '../api/apiClient';
import { formatPrice } from '../utils/formatters';
import { DuesPaymentSheet } from '../components/club/DuesPaymentSheet';
import { ClubEventsTeaser } from '../components/club/ClubEventsTeaser';
import { ClubIdentityHeader } from '../components/club/ClubIdentityHeader';
import { ClubLockedNotice } from '../components/club/ClubLockedNotice';
import { ClubQualityFacts } from '../components/club/ClubQualityFacts';
import { FoxEmpty } from '../components/feed/FoxEmpty';
import { WelcomeScene, memberCountCaption } from '../components/onboarding/WelcomeScene';
import { Toast } from '../components/Toast';
import foxInviteArt from '../assets/mascot/fox-invite.png';
import foxErrorArt from '../assets/mascot/fox-error.png';

/**
 * До скольки участников клуб ещё «только собирается»: при таком составе И полном отсутствии
 * встреч приглашённому честно обещать, что он будет одним из первых.
 */
const FIRST_MEMBERS_THRESHOLD = 5;

export const InvitePage: FC = () => {
  useBackButton(true);
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const clubQuery = useClubByInviteQuery(code);
  const myClubsQuery = useMyClubsQuery();
  const joinMutation = useJoinByInviteMutation();
  const applyMutation = useApplyToClubMutation();
  const completeWelcome = useCompleteTourMutation();
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);

  const [actionError, setActionError] = useState<string | null>(null);
  const [joined, setJoined] = useState(false);
  const [applied, setApplied] = useState(false);
  const [answerText, setAnswerText] = useState('');
  const [welcomeError, setWelcomeError] = useState<string | null>(null);
  const [showChatHint, setShowChatHint] = useState(false);
  /**
   * Оплата взноса сразу после вступления в платный клуб — вместо двух пересадок
   * («Добро пожаловать» → страница клуба) ради одной кнопки (решение PO 2026-07-30).
   * sheet — шит открыт · deferred — закрыт без оплаты (человек уже участник) · claimed — заявил.
   */
  const [duesStage, setDuesStage] = useState<'none' | 'sheet' | 'deferred' | 'claimed'>('none');
  /** Тот же шит, но для должника, который открыл приглашение в клуб, где уже состоит. */
  const [showDebtorSheet, setShowDebtorSheet] = useState(false);
  // Кнопка «В чат» стоит вверху экрана, а форма заявки — внизу: подсказке нужно к ней прокрутить.
  const ctaRef = useRef<HTMLDivElement>(null);

  // Велком-сцена: инвайт — главная точка входа новичка, интро ему не показывается вовсе
  // (deep-link), поэтому продукт рассказывает сцена ПОСЛЕ вступления. Один раз за аккаунт.
  const isNewbie = !!user && !user.onboardingTours.includes('WELCOME');

  const club = clubQuery.data;
  const loading = clubQuery.isPending;
  const joining = joinMutation.isPending || applyMutation.isPending;

  // club-invites (кадр G): в полный клуб прямое вступление невозможно — приглашение
  // деградирует в обычную заявку, организатор может расширить клуб из инбокса.
  const isClubFull = !!club && club.memberCount >= club.memberLimit;

  // Приглашение из Telegram в клуб «по заявке» ведёт на ОДОБРЕНИЕ, а не сразу в состав
  // (решение PO 2026-07-30; бэкенд отдаёт признак по коду ссылки и сам отбивает прямое
  // вступление по ней). Прямая ссылка «Скопировать» приходит с false — по ней вступают сразу.
  const needsApplication = !!club && (club.inviteRequiresApplication || isClubFull);

  // Приглашение открыл человек, который уже в клубе (active / frozen / expired — место
  // занято): вместо CTA вступления — «Перейти в клуб». Отфильтровать его в нативном
  // пикере Telegram нельзя (пикер не сообщает и не ограничивает выбор), поэтому
  // страхуемся на посадочной; бэкенд повторное вступление и так отбивает (409).
  const myMembership = myClubsQuery.data?.find((m) => m.clubId === club?.id);
  const isAlreadyMember = !!myMembership && ['active', 'frozen', 'expired'].includes(myMembership.status);
  // Должник: место в клубе занято, но доступа нет — frozen (не передал первый взнос) или
  // expired (не продлил). Ему на посадочной нужна не дверь в клуб, а оплата.
  const isDebtor = myMembership?.status === 'frozen' || myMembership?.status === 'expired';

  // Реквизиты СБП приходят ТОЛЬКО участнику (ClubService.getClub: includeRequisites), поэтому
  // берём их отдельным запросом и лишь когда шит оплаты реально нужен: сразу после вступления
  // в платный клуб или должнику, открывшему приглашение.
  const needsRequisites = duesStage !== 'none' || showDebtorSheet;
  const clubWithRequisites = useClubQuery(needsRequisites ? club?.id : undefined).data;

  // Клуб только собирается: блоки качества и афиши у него молчат (fail-soft), и без этой
  // строки экран схлопнулся бы к голому описанию. Запрос тот же, что грузит ClubQualityFacts
  // ниже, — react-query отдаёт его из кэша, второго обращения к сети нет.
  const qualityQuery = useClubQualityQuery(club?.id);
  const isJustStarting = !!club
    && qualityQuery.data?.totalMeetings === 0
    && club.memberCount <= FIRST_MEMBERS_THRESHOLD;

  const handleJoin = () => {
    if (!code) return;
    haptic.impact('medium');
    setActionError(null);
    joinMutation.mutate(code, {
      onSuccess: () => {
        haptic.notify('success');
        const joinedClub = clubQuery.data;
        // Платный клуб: взнос предлагаем здесь же — раньше человека вели на страницу клуба
        // ради одной кнопки «Оплатить взнос».
        if (joinedClub && joinedClub.subscriptionPrice > 0) {
          setJoined(true);
          setDuesStage('sheet');
          return;
        }
        // Бесплатный клуб знакомому пользователю: подтверждать нечего — ведём прямо в клуб.
        // Новичку вместо этого показывается велком-сцена (его первое знакомство с продуктом).
        if (joinedClub && !isNewbie) {
          navigate(`/clubs/${joinedClub.id}`, { replace: true });
          return;
        }
        setJoined(true);
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
  // (ловушка среза 1: профиль в сторе = гейт Layout; см. useCompleteTourMutation).
  // Здесь гейт закрыт startParam'ом, но порядок сохраняем — он единственный корректный везде.
  const handleWelcomeCta = async () => {
    if (completeWelcome.isPending) return;
    haptic.impact('medium');
    try {
      const freshUser = await completeWelcome.mutateAsync('WELCOME');
      navigate(`/clubs/${club.id}`);
      setUser(freshUser);
    } catch {
      haptic.notify('error');
      setWelcomeError('Не удалось продолжить. Проверьте связь и попробуйте ещё раз.');
    }
  };

  const isPaid = club.subscriptionPrice > 0;

  /** Шит взноса: реквизиты подгружены — открываем, ещё грузятся — держим спиннер вместо него. */
  const renderDuesSheet = (onClose: () => void, onClaimed: () => void) => {
    if (!clubWithRequisites) {
      return (
        <div className="rd-spinner-row" style={{ paddingTop: 24 }}>
          <Spinner size="m" />
        </div>
      );
    }
    return (
      <DuesPaymentSheet
        clubId={club.id}
        price={club.subscriptionPrice}
        paymentLink={clubWithRequisites.paymentLink}
        paymentMethodNote={clubWithRequisites.paymentMethodNote}
        onClose={onClose}
        onClaimed={onClaimed}
      />
    );
  };

  if (joined) {
    // Платный клуб: вступление и взнос — один экран. Пока шит открыт (или человек закрыл его,
    // не заплатив), под ним стоит короткий итог; велком-сцена новичку показывается ПОСЛЕ денег,
    // чтобы рассказ про продукт не вклинивался между решением и оплатой (решение PO 2026-07-30).
    if (isPaid && duesStage === 'sheet') {
      return (
        <div className="rd-page">
          <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
            <div className="rd-title">Вы вступили в клуб</div>
            <div className="rd-sub">Осталось передать взнос организатору.</div>
          </div>
          {renderDuesSheet(() => setDuesStage('deferred'), () => setDuesStage('claimed'))}
        </div>
      );
    }
    if (isPaid && duesStage !== 'none' && !isNewbie) {
      const claimed = duesStage === 'claimed';
      return (
        <div className="rd-page">
          <div className="rd-glass rd-empty" style={{ marginTop: 40 }}>
            <div className="rd-title">{claimed ? 'Оплата на проверке' : 'Вы вступили в клуб'}</div>
            <div className="rd-sub">
              {claimed
                ? 'Организатор проверит взнос и откроет доступ — мы сообщим.'
                : 'Взнос можно передать позже: доступ к активностям организатор откроет после него.'}
            </div>
            {!claimed && (
              <button
                type="button"
                className="rd-btn-primary"
                onClick={() => { haptic.impact('medium'); setDuesStage('sheet'); }}
                style={{ maxWidth: 240, margin: '0 auto 8px' }}
              >
                Оплатить взнос
              </button>
            )}
            <button
              type="button"
              className={claimed ? 'rd-btn-primary' : 'rd-btn-outline'}
              onClick={() => { haptic.impact('light'); navigate(`/clubs/${club.id}`); }}
              style={{ maxWidth: 240, margin: '0 auto' }}
            >
              Перейти в клуб
            </button>
          </div>
        </div>
      );
    }
    // Новичок: вместо сухого «Добро пожаловать» — велком-сцена (кадр A/B). CTA помечает
    // онбординг дверью MEMBER — карусель с дверями такому человеку больше не показывается.
    // Взнос уже заявлен → отдельный вариант сцены: доступа ещё нет, поздравлять «Ты в клубе!»
    // и советовать оплату (она сделана) — врать человеку.
    if (isNewbie) {
      return (
        <>
          <WelcomeScene
            variant={isPaid ? (duesStage === 'claimed' ? 'paidClaimed' : 'paid') : 'free'}
            clubName={club.name}
            clubCaption={`${club.city} · ${isPaid ? formatPrice(club.subscriptionPrice) : memberCountCaption(club.memberCount)}`}
            clubAvatarUrl={club.avatarUrl}
            ctaPending={completeWelcome.isPending}
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
          clubCaption={`${club.city} · ${isClubFull ? 'мест пока нет' : 'ждём одобрения'}`}
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
            {isClubFull
              ? `В клубе «${club.name}» сейчас нет мест. Организатор увидит вашу заявку и может расширить клуб — мы сообщим о решении.`
              : `Клуб «${club.name}» принимает по заявке. Организатор посмотрит её и откроет доступ — мы сообщим о решении.`}
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

  const hasAbout = !!club.description || !!club.rules;
  // Чат показываем той же пилюлей, что и на странице клуба, но она ведёт не в чат, а к подсказке:
  // дверь в чат открывает вступление. Достаточно самого факта привязки — без включённой «двери»
  // чат у клуба всё равно есть, меняется только текст подсказки (обещать авто-впуск ботом нельзя).
  const showChatPill = club.chatLinked;
  // В платном клубе кнопка сразу называет оба шага: тап вступает и открывает выбор способа
  // оплаты здесь же. Раньше между ними лежали два экрана, на которых нечего было решать.
  const joinCtaLabel = isClubFull
    ? 'Попроситься в клуб'
    : needsApplication
      ? 'Отправить заявку'
      : isPaid
        ? 'Вступить и оплатить взнос'
        : 'Вступить в клуб';

  const chatHintText = isAlreadyMember
    ? 'Чат клуба живёт внутри — откройте клуб и заходите.'
    : club.chatDoorEnabled
      ? 'Чат клуба открыт участникам. Вступите — и бот впустит вас туда.'
      : 'У клуба есть чат. Организатор позовёт вас туда после вступления.';

  // Кнопка из подсказки: прямое вступление делаем сразу, а заявку — только доведя человека
  // до формы внизу, иначе он не увидит ни вопроса организатора, ни ошибки о пустом ответе.
  const handleChatHintCta = () => {
    setShowChatHint(false);
    haptic.impact('light');
    // Уже в клубе, но ссылки на чат нет — это frozen/expired (доступа нет, ссылка не выдаётся):
    // звать его вступать нельзя, бэкенд ответит 409. Ведём в клуб, там ждёт claim-флоу взноса.
    if (isAlreadyMember) {
      navigate(`/clubs/${club.id}`, { replace: true });
      return;
    }
    if (needsApplication) {
      ctaRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    handleJoin();
  };

  return (
    <div className="rd-page">
      {/* Приглашение показывает тот же клуб, что и его страница, поэтому и шапка та же
          (ClubIdentityHeader): человек, вступив, попадает на визуально знакомый экран.
          В углу обложки — метка вместо кнопок роли: у приглашённого роли ещё нет. */}
      <ClubIdentityHeader
        club={club}
        avatarEditable={false}
        coverActions={<span className="rd-invite-badge">✉ Приглашение</span>}
      />

      {/* Кто зовёт — сразу под параметрами клуба, а не сноской под кнопкой: клуб человеку
          незнаком, и доверять на этом экране пока можно только человеку.
          В ответе лежит имя ВЛАДЕЛЬЦА (ClubService.getClubByInviteCode), а ссылку мог прислать
          любой участник — код общий на клуб и отправителя не знает, поэтому подпись говорит
          «организатор», а не «вас зовёт». */}
      {club.ownerFirstName && (
        <div className="rd-invite-org">
          <span className="rd-invite-org-ava" aria-hidden="true">{club.ownerFirstName.charAt(0).toUpperCase()}</span>
          <span className="rd-invite-org-tx">
            <b>Организатор — {club.ownerFirstName}{club.ownerLastName ? ` ${club.ownerLastName}` : ''}</b>
            <span>{isPaid ? 'взнос вы передаёте напрямую, минуя платформу' : 'отвечает за клуб и встречи'}</span>
          </span>
        </div>
      )}

      {isJustStarting && (
        <div className="rd-cl-chip rd-accent">
          <span aria-hidden="true">🌱</span>
          <span>Клуб только собирается — вы будете одним из первых</span>
        </div>
      )}

      {(hasAbout || showChatPill) && (
        <>
          <div className="rd-section-sub-h">О клубе</div>
          <div className="rd-club-about">
            {club.description && <div className="rd-txt">{club.description}</div>}
            {club.rules && (
              <>
                <div className="rd-rules-h">Правила</div>
                <div className="rd-txt">{club.rules}</div>
              </>
            )}
            {showChatPill && (
              <div className="rd-club-chatrow">
                <button
                  type="button"
                  className="rd-club-chatpill"
                  onClick={() => { haptic.impact('light'); setShowChatHint((shown) => !shown); }}
                  aria-expanded={showChatHint}
                >
                  <span aria-hidden="true">💬</span>
                  В чат
                </button>
                {showChatHint && (
                  <>
                    {/* Завеса на весь экран: тап мимо подсказки закрывает её — на телефоне это
                        единственный привычный способ выйти, клавиши Esc там нет. */}
                    <div className="rd-chathint-veil" onClick={() => setShowChatHint(false)} />
                    {/* Без role="dialog": глобальное правило brand-theme.css прибивает всё с этой
                        ролью к низу экрана через !important (там живут боттом-шиты). Раскрытие
                        и так объявлено через aria-expanded на самой пилюле. */}
                    <div className="rd-chathint">
                      <div className="rd-chathint-tx">{chatHintText}</div>
                      <button type="button" className="rd-chathint-cta" onClick={handleChatHintCta}>
                        {isAlreadyMember ? 'Перейти в клуб' : joinCtaLabel}
                      </button>
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        </>
      )}

      {/* Жизнь клуба и афиша — те же публичные блоки, что видит гость на странице клуба.
          Оба fail-soft: у молодого клуба просто не рендерятся. Строку-замок афише не даём:
          то же самое говорит плашка сразу под ней. */}
      <ClubQualityFacts clubId={club.id} memberCount={club.memberCount} />

      <ClubEventsTeaser clubId={club.id} />

      <ClubLockedNotice
        title="Активности клуба доступны участникам"
        description="Содержимое клуба открывается после вступления."
      />

      {!isAlreadyMember && isClubFull && (
        <div className="rd-cl-chip">
          <span aria-hidden="true">👥</span>
          <span>В клубе кончились места — вы всё равно можете попроситься, организатор может расширить клуб</span>
        </div>
      )}

      {/* Заявка + вопрос организатора: ответ обязателен — поле в общем стиле форм. */}
      {!isAlreadyMember && needsApplication && club.applicationQuestion && (
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

      <div className="rd-cta-wrap" ref={ctaRef}>
        {isAlreadyMember ? (
          <>
            <div className="rd-cl-chip">
              <span aria-hidden="true">{isDebtor ? '💸' : '✓'}</span>
              <span>
                {isDebtor
                  ? 'Вы уже в этом клубе — остался взнос, после него организатор откроет доступ'
                  : 'Вы уже состоите в этом клубе'}
              </span>
            </div>
            {/* Должник пришёл по ссылке в клуб, где уже состоит: ему нужна не дверь в клуб,
                а оплата — предлагаем её здесь же, а не через две пересадки. */}
            {isDebtor && (
              <button
                type="button"
                className="rd-btn-primary"
                onClick={() => { haptic.impact('medium'); setShowDebtorSheet(true); }}
                style={{ marginBottom: 8 }}
              >
                Оплатить взнос
              </button>
            )}
            <button
              type="button"
              className={isDebtor ? 'rd-btn-outline' : 'rd-btn-primary'}
              onClick={() => { haptic.impact('light'); navigate(`/clubs/${club.id}`, { replace: true }); }}
            >
              Перейти в клуб
            </button>
          </>
        ) : needsApplication ? (
          <>
            <button type="button" className="rd-btn-primary" onClick={handleApply} disabled={joining}>
              {joining ? <Spinner size="s" /> : joinCtaLabel}
            </button>
            <div className="rd-cta-hint">
              {isClubFull
                ? 'Заявка попадёт к организатору — он решает, расширять ли клуб'
                : 'Организатор посмотрит заявку и откроет доступ'}
            </div>
          </>
        ) : (
          <>
            <button type="button" className="rd-btn-primary" onClick={handleJoin} disabled={joining}>
              {joining ? <Spinner size="s" /> : joinCtaLabel}
            </button>
            {/* Кнопка обещает и вступление, и оплату — подпись объясняет, что за ней будет:
                выбор способа, деньги мимо платформы, ничего не списывается автоматически. */}
            {isPaid && (
              <div className="rd-cta-hint">
                Дальше выберете способ — СБП или наличными. Взнос вы передаёте организатору
                напрямую, платформа денег не касается и ничего не списывает.
              </div>
            )}
          </>
        )}
      </div>

      {/* Должник открыл приглашение в свой же клуб: шит оплаты прямо здесь. После заявления
          об оплате ведём в клуб — там висит «Оплата на проверке». */}
      {showDebtorSheet && renderDuesSheet(
        () => setShowDebtorSheet(false),
        () => { setShowDebtorSheet(false); navigate(`/clubs/${club.id}`, { replace: true }); },
      )}
    </div>
  );
};
