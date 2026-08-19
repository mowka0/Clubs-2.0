import { FC, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { BotRightsStep, hasAllBotRights } from '../components/club/setup/BotRightsStep';
import { ClubSetupAboutStep } from '../components/club/setup/ClubSetupAboutStep';
import { ClubSetupCityStep } from '../components/club/setup/ClubSetupCityStep';
import { ClubSetupCoverStep } from '../components/club/setup/ClubSetupCoverStep';
import { ClubSetupNameStep } from '../components/club/setup/ClubSetupNameStep';
import type { ClubSetupDraft, ClubSetupStepProps } from '../components/club/setup/types';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { ApiError } from '../api/apiClient';
import { useChatLinkStatusQuery } from '../queries/chatLink';
import { useClubQuery, useUpdateClubMutation } from '../queries/clubs';
import {
  CLUB_SETUP_TOTAL_STEPS,
  CLUB_SETUP_STEPS_WITHOUT_RIGHTS,
  clearClubSetupProgress,
  readClubSetupStep,
  saveClubSetupStep,
} from '../utils/clubSetupProgress';

/** Черновик на старте: ничего не трогали, шаги показывают то, что лежит в клубе. */
const EMPTY_DRAFT: ClubSetupDraft = {
  name: null,
  city: null,
  description: null,
  interests: null,
  memberLimit: null,
};

/**
 * Наполнение клуба, рождённого из чата, — перед тем как показать его участникам.
 *
 * Зачем мастер вообще: клуб создаётся из чата пустым (известно только название группы), и
 * показывать такую страницу 184 участникам — значит потратить первое впечатление впустую.
 * Орг сначала наполняет клуб, и только потом сам его презентует.
 *
 * Порядок шагов не случаен: сперва то, что человек знает про свой чат наизусть (название),
 * потом обязательный город, и лишь затем то, над чем нужно подумать (описание) и повозиться
 * (обложка). Пропустить можно всё, кроме города: без него не работает недельный опрос.
 *
 * Разметка следует мокапу `docs/design/club-from-chat/mockups/02-club-setup-wizard.html`:
 * один вопрос на экран, поля-карточки с капс-метками, финальный шаг показывает превью
 * страницы — чтобы человек увидел, что именно получат участники.
 *
 * Сама страница держит только общее для всех шагов: номер шага, черновик, сохранение и
 * шкалу прогресса. Вопросы живут по файлу на шаг в `components/club/setup/`.
 *
 * BackButton страница показывает сама, как и все вложенные экраны: уходя со страницы клуба,
 * та в cleanup прячет кнопку, и без своей подписки мастер остаётся с телеграмным «закрыть»
 * (баг staging 2026-08-17). Обработчик — стандартный `navigate(-1)`: шаги живут в истории,
 * поэтому «назад» отматывает шаг, а с первого возвращает на страницу клуба, откуда пришли.
 */
export const ClubSetupWizard: FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const clubQuery = useClubQuery(id);
  const updateClub = useUpdateClubMutation();
  // Права бота Telegram выдаёт прямо при добавлении по ссылке, поэтому шаг про них нужен не
  // всегда: он появляется, только если чего-то не хватает (правка PO 2026-08-18).
  const chatLinkQuery = useChatLinkStatusQuery(id, { enabled: Boolean(id) });
  // До ранних return: количество хуков в рендере меняться не должно.
  useBackButton(true);

  const club = clubQuery.data;
  // Шаг живёт в URL, а не в состоянии: тогда кнопка «назад» Telegram и свайп от кромки
  // (SwipeNavigator ходит по истории роутера) возвращают на предыдущий шаг сами, без
  // отдельной навигации внутри компонента.
  const [searchParams, setSearchParams] = useSearchParams();
  const stepParam = Number(searchParams.get('step'));
  const chatStatus = chatLinkQuery.data;
  // Пока статус едет, считаем шаг прав нужным: ошибиться в эту сторону безопаснее — шкала не
  // прыгнет назад, а лишний шаг человек закроет одной кнопкой «Готово».
  const hasRightsStep = !(chatStatus && hasAllBotRights(chatStatus));
  const totalSteps = hasRightsStep ? CLUB_SETUP_TOTAL_STEPS : CLUB_SETUP_STEPS_WITHOUT_RIGHTS;
  const urlStep = Number.isInteger(stepParam) && stepParam >= 1 && stepParam <= totalSteps ? stepParam : 1;
  const [draft, setDraft] = useState<ClubSetupDraft>(EMPTY_DRAFT);
  const patchDraft = (patch: Partial<ClubSetupDraft>) => setDraft((prev) => ({ ...prev, ...patch }));

  // Вернулся в приложение — продолжает с того шага, где остановился. URL при новом запуске
  // пустой, поэтому прогресс держим в localStorage и подставляем replace'ом: в историю такой
  // шаг попасть не должен, иначе «назад» уводило бы на него же.
  //
  // Ровно один раз за открытие мастера (флаг в ref): у первого шага в URL нет `step`, и без
  // флага возврат на него тут же подменялся сохранённым шагом — «назад» с первого шага не
  // работал вовсе (баг PO 2026-08-18).
  const progressRestored = useRef(false);
  useEffect(() => {
    if (progressRestored.current || !id) return;
    progressRestored.current = true;
    if (searchParams.get('step')) return;
    const saved = readClubSetupStep(id);
    if (saved > 1) setSearchParams({ step: String(saved) }, { replace: true });
  }, [id, searchParams, setSearchParams]);

  useEffect(() => {
    if (id) saveClubSetupStep(id, urlStep, totalSteps);
  }, [id, urlStep, totalSteps]);

  if (clubQuery.isPending) return null;
  if (!club) return null;

  /**
   * Мастер пройден: ставим отметку на сервере и стираем локальный прогресс.
   *
   * Отметка нужна серверная (V82): раньше баннер держался на «городе нет» и пропадал сразу
   * после второго шага — описание с обложкой доделать было уже негде (баг PO 2026-08-19).
   * Уходим в клуб не дожидаясь ответа: пропущенные шаги и так разрешены, а держать человека
   * на экране ради технической отметки незачем — упавший запрос вернёт баннер, и только.
   */
  const finish = () => {
    clearClubSetupProgress(club.id);
    if (!club.setupCompleted) updateClub.mutate({ id: club.id, body: { setupCompleted: true } });
    navigate(`/clubs/${club.id}`, { replace: true });
  };
  // Город обязателен, поэтому шаг дальше второго без него — рассинхрон (например, город
  // сбросили в управлении): возвращаем на него, а не показываем недостижимый прогресс.
  const step = club.cityId === null && urlStep > 2 ? 2 : urlStep;
  const goToStep = (next: number) => setSearchParams({ step: String(next) });

  /**
   * Сохраняем на каждом переходе, а не одной пачкой в конце: человек может закрыть приложение
   * на любом шаге, и уже введённое должно остаться. Ошибку показываем, но с шага не уводим.
   */
  const saveAndNext = (body: Record<string, unknown>, next: number) => {
    haptic.impact('light');
    updateClub.mutate({ id: club.id, body }, { onSuccess: () => goToStep(next) });
  };

  // 429 стоит отделить от прочих сбоев: «попробуйте ещё раз» здесь вредный совет — повтор
  // упирается в тот же лимит, помогает только пауза.
  const isRateLimited = updateClub.error instanceof ApiError && updateClub.error.status === 429;
  const saveError = updateClub.isError && (
    <p className="rd-wz-err" role="alert">
      {isRateLimited
        ? 'Слишком много запросов подряд. Подождите минуту и попробуйте снова.'
        : 'Не получилось сохранить. Попробуйте ещё раз.'}
    </p>
  );
  const stepProps: ClubSetupStepProps = {
    club,
    draft,
    onDraftChange: patchDraft,
    saving: updateClub.isPending,
    error: saveError,
    onSaveAndNext: saveAndNext,
    onGoToStep: goToStep,
  };

  return (
    <div className="rd-page rd-wz">
      <div className="rd-wz-bar">
        {Array.from({ length: totalSteps }, (_, i) => (
          <span
            key={i}
            className={i + 1 === step ? 'rd-wz-seg rd-on' : i + 1 < step ? 'rd-wz-seg rd-done' : 'rd-wz-seg'}
          />
        ))}
      </div>
      <div className="rd-wz-cap">Шаг {step} из {totalSteps}</div>

      {step === 1 && <ClubSetupNameStep {...stepProps} />}
      {step === 2 && <ClubSetupCityStep {...stepProps} />}
      {step === 3 && <ClubSetupAboutStep {...stepProps} />}
      {step === 4 && (
        <ClubSetupCoverStep
          club={club}
          draft={draft}
          hasRightsStep={hasRightsStep}
          onGoToStep={goToStep}
          onFinish={finish}
        />
      )}
      {step === 5 && <BotRightsStep clubId={club.id} onFinish={finish} />}
    </div>
  );
};
