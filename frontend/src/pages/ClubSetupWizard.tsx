import { FC, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CityPicker } from '../components/CityPicker';
import { ClubAvatarButton } from '../components/club/ClubAvatarButton';
import { ClubCoverButton } from '../components/club/ClubCoverButton';
import { ClubInterestsPicker } from '../components/club/ClubInterestsPicker';
import { BotRightsStep, hasAllBotRights } from '../components/club/BotRightsStep';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useChatLinkStatusQuery } from '../queries/chatLink';
import { useClubQuery, useUpdateClubMutation } from '../queries/clubs';
import {
  CLUB_SETUP_TOTAL_STEPS,
  CLUB_SETUP_STEPS_WITHOUT_RIGHTS,
  clearClubSetupProgress,
  readClubSetupStep,
  saveClubSetupStep,
} from '../utils/clubSetupProgress';
import type { CityDto } from '../types/api';

/** Потолок названия клуба, совпадает с VARCHAR(60) в схеме. */
const NAME_MAX = 60;
/** Потолок описания, совпадает с VARCHAR(500). */
const DESCRIPTION_MAX = 500;
/** Границы размера клуба — те же, что в CHECK-констрейнте схемы (V81) и в валидации DTO. */
const MEMBER_LIMIT_MIN = 1;
const MEMBER_LIMIT_MAX = 500;

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
 * Темы идут вместе с описанием — это одна мысль «о чём клуб», просто вторая половина
 * записана словами общего словаря, по которым работает поиск.
 *
 * Разметка следует мокапу `docs/design/club-from-chat/mockups/02-club-setup-wizard.html`:
 * один вопрос на экран, поля-карточки с капс-метками, финальный шаг показывает превью
 * страницы — чтобы человек увидел, что именно получат участники.
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
  const totalSteps = chatStatus && hasAllBotRights(chatStatus) ? CLUB_SETUP_STEPS_WITHOUT_RIGHTS : CLUB_SETUP_TOTAL_STEPS;
  const urlStep = Number.isInteger(stepParam) && stepParam >= 1 && stepParam <= totalSteps ? stepParam : 1;
  const [name, setName] = useState<string | null>(null);
  const [city, setCity] = useState<CityDto | null>(null);
  const [description, setDescription] = useState<string | null>(null);
  const [interests, setInterests] = useState<string[] | null>(null);
  const [memberLimit, setMemberLimit] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

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
    if (id) saveClubSetupStep(id, urlStep);
  }, [id, urlStep]);

  if (clubQuery.isPending) return null;
  if (!club) return null;

  const nameValue = name ?? club.name;
  const descriptionValue = description ?? club.description;
  const interestsValue = interests ?? club.interests;
  const cityLabel = city?.name ?? (club.cityId ? club.city : null);
  const memberLimitValue = memberLimit ?? String(club.memberLimit);
  // null = введено не число или значение вне границ схемы: кнопка «Дальше» тогда заблокирована,
  // и мы не отправляем заведомо отбиваемый бэком PATCH.
  const memberLimitNumber = /^\d+$/.test(memberLimitValue.trim())
    ? Number(memberLimitValue) >= MEMBER_LIMIT_MIN && Number(memberLimitValue) <= MEMBER_LIMIT_MAX
      ? Number(memberLimitValue)
      : null
    : null;
  // Мастер пройден — прогресс стираем, иначе кнопка на странице клуба вечно звала бы
  // «продолжить» с последнего шага.
  const finish = () => {
    clearClubSetupProgress(club.id);
    navigate(`/clubs/${club.id}`, { replace: true });
  };
  // Город обязателен, поэтому шаг дальше второго без него — рассинхрон (например, город
  // сбросили в управлении): возвращаем на него, а не показываем недостижимый прогресс.
  const step = club.cityId === null && urlStep > 2 ? 2 : urlStep;
  const setStep = (next: number) => setSearchParams({ step: String(next) });

  /**
   * Сохраняем на каждом переходе, а не одной пачкой в конце: человек может закрыть приложение
   * на любом шаге, и уже введённое должно остаться. Ошибку показываем, но с шага не уводим.
   */
  const saveAndNext = (body: Record<string, unknown>, next: number) => {
    haptic.impact('light');
    updateClub.mutate({ id: club.id, body }, { onSuccess: () => setStep(next) });
  };

  const saveError = updateClub.isError && (
    <p className="rd-wz-err" role="alert">Не получилось сохранить. Попробуйте ещё раз.</p>
  );

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

      {step === 1 && (
        <>
          <h1 className="rd-wz-q">Как назовём клуб?</h1>
          <p className="rd-wz-qsub">Взяли название чата — поменяйте, если хочется.</p>

          <div className="rd-wz-lbl">Аватар</div>
          <div className="rd-wz-ava-row">
            <ClubAvatarButton clubId={club.id} clubName={nameValue} avatarUrl={club.avatarUrl} editable />
            <span className="rd-wz-hint">Кружок клуба. Видно в списках и в шапке.</span>
          </div>

          <div className="rd-wz-lbl">Название</div>
          <input
            className="rd-input"
            value={nameValue}
            maxLength={NAME_MAX}
            onChange={(e) => setName(e.target.value)}
            aria-label="Название клуба"
          />

          {/* Размер подставлен из чата (getChatMemberCount при рождении клуба) — Telegram считает
              вместе с ботами, поэтому число приблизительное и правится руками. */}
          <div className="rd-wz-lbl">Сколько человек в клубе</div>
          <input
            className="rd-input"
            type="number"
            inputMode="numeric"
            min={MEMBER_LIMIT_MIN}
            max={MEMBER_LIMIT_MAX}
            value={memberLimitValue}
            onChange={(e) => setMemberLimit(e.target.value)}
            aria-label="Размер клуба"
          />
          <span className="rd-wz-hint">Взяли из чата. Потолок — {MEMBER_LIMIT_MAX}, потом можно поменять.</span>

          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={!nameValue.trim() || memberLimitNumber === null || updateClub.isPending}
            onClick={() =>
              memberLimitNumber !== null &&
              saveAndNext({ name: nameValue.trim(), memberLimit: memberLimitNumber }, 2)
            }
          >
            Дальше
          </button>
          {saveError}
          <div className="rd-wz-note">Аватар можно добавить потом</div>
        </>
      )}

      {step === 2 && (
        <>
          <h1 className="rd-wz-q">В каком городе встречаетесь?</h1>
          <p className="rd-wz-qsub">Без города бот не сможет предлагать встречи рядом.</p>

          {/* Единственный обязательный шаг — говорим об этом прямо, а не прячем в отключённой кнопке. */}
          <div className="rd-wz-req"><span className="rd-wz-dot" aria-hidden="true" />Обязательный шаг</div>

          <div className="rd-wz-lbl">Город</div>
          <button
            type="button"
            className={cityLabel ? 'rd-input rd-wz-pick' : 'rd-input rd-wz-pick rd-ph'}
            onClick={() => { haptic.impact('light'); setPickerOpen(true); }}
          >
            {cityLabel ?? 'Выбрать город'}
          </button>

          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={!city || updateClub.isPending}
            onClick={() => city && saveAndNext({ cityId: city.id }, 3)}
          >
            Дальше
          </button>
          {saveError}
          <div className="rd-wz-note">Пропустить нельзя — это единственное исключение</div>

          {pickerOpen && (
            <CityPicker
              value={city}
              onChange={(next) => { setCity(next); setPickerOpen(false); }}
              onClose={() => setPickerOpen(false)}
            />
          )}
        </>
      )}

      {step === 3 && (
        <>
          <h1 className="rd-wz-q">О чём ваш клуб?</h1>
          <p className="rd-wz-qsub">Это первое, что прочитают участники, открыв страницу.</p>

          <div className="rd-wz-lbl">Описание</div>
          <textarea
            className="rd-textarea rd-wz-area"
            value={descriptionValue}
            maxLength={DESCRIPTION_MAX}
            placeholder="Бегаем по вторникам и субботам в Сокольниках. Темп разный, догоняем всех."
            onChange={(e) => setDescription(e.target.value)}
            aria-label="Описание клуба"
          />

          {/* Темы — продолжение описания: «о чём клуб» словами общего словаря. По ним же
              работает поиск, поэтому свободный ввод здесь только последним шагом
              (club-interests.md). Полка чипов подставляется по категории клуба. */}
          <div className="rd-wz-lbl">Темы</div>
          <ClubInterestsPicker category={club.category} value={interestsValue} onChange={setInterests} />

          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={updateClub.isPending}
            onClick={() => saveAndNext({ description: descriptionValue.trim(), interests: interestsValue }, 4)}
          >
            Дальше
          </button>
          <button type="button" className="rd-ghost-btn rd-wz-skip" onClick={() => setStep(4)}>
            Пропустить
          </button>
          {saveError}
        </>
      )}

      {step === 4 && (
        <>
          <h1 className="rd-wz-q">Обложка клуба</h1>
          <p className="rd-wz-qsub">Так страницу увидят участники.</p>

          <div className="rd-wz-cover-slot">
            {club.coverUrl
              ? <img className="rd-wz-cover-img" src={club.coverUrl} alt="" />
              : <span className="rd-wz-cover-empty">Обложка не выбрана</span>}
            <div className="rd-wz-cover-btn">
              <ClubCoverButton clubId={club.id} hasCover={!!club.coverUrl} />
            </div>
          </div>

          {/* Превью — единственное место, где орг видит клуб глазами участника до презентации. */}
          <div className="rd-wz-lbl">Как это выглядит</div>
          <div className="rd-wz-preview">
            <div className="rd-wz-pv-cover">
              {club.coverUrl && <img src={club.coverUrl} alt="" />}
            </div>
            <div className="rd-wz-pv-ava">
              {club.avatarUrl
                ? <img src={club.avatarUrl} alt="" />
                : <span>{nameValue.trim().charAt(0).toUpperCase()}</span>}
            </div>
            <div className="rd-wz-pv-name">{nameValue}</div>
            <div className="rd-wz-pv-meta">
              {[cityLabel, ...interestsValue.slice(0, 2)].filter(Boolean).join(' · ') || 'Клуб чата'}
            </div>
          </div>

          {/* Права уже выданы при добавлении бота — четвёртый шаг последний. */}
          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            onClick={() => (totalSteps === CLUB_SETUP_TOTAL_STEPS ? setStep(5) : finish())}
          >
            {totalSteps === CLUB_SETUP_TOTAL_STEPS ? 'Дальше' : 'Готово'}
          </button>
          <button
            type="button"
            className="rd-ghost-btn rd-wz-skip"
            onClick={() => (totalSteps === CLUB_SETUP_TOTAL_STEPS ? setStep(5) : finish())}
          >
            Пропустить обложку
          </button>
        </>
      )}

      {step === 5 && <BotRightsStep clubId={club.id} onFinish={finish} />}
    </div>
  );
};
