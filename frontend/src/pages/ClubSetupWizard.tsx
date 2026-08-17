import { FC, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CityPicker } from '../components/CityPicker';
import { ClubAvatarButton } from '../components/club/ClubAvatarButton';
import { ClubCoverButton } from '../components/club/ClubCoverButton';
import { ClubInterestsPicker } from '../components/club/ClubInterestsPicker';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useClubQuery, useUpdateClubMutation } from '../queries/clubs';
import type { CityDto } from '../types/api';

/** Сколько шагов в мастере — от него считается полоска прогресса. */
const TOTAL_STEPS = 4;
/** Потолок названия клуба, совпадает с VARCHAR(60) в схеме. */
const NAME_MAX = 60;
/** Потолок описания, совпадает с VARCHAR(500). */
const DESCRIPTION_MAX = 500;

/** Ключ прогресса в localStorage: шаг переживает закрытие приложения, URL — нет. */
function progressKey(clubId: string): string {
  return `club-setup-step:${clubId}`;
}

function readSavedStep(clubId: string): number {
  const raw = Number(localStorage.getItem(progressKey(clubId)));
  return Number.isInteger(raw) && raw >= 1 && raw <= TOTAL_STEPS ? raw : 1;
}

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
 */
export const ClubSetupWizard: FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const clubQuery = useClubQuery(id);
  const updateClub = useUpdateClubMutation();

  const club = clubQuery.data;
  // Шаг живёт в URL, а не в состоянии: тогда кнопка «назад» Telegram и свайп от кромки
  // (SwipeNavigator ходит по истории роутера) возвращают на предыдущий шаг сами, без
  // отдельной навигации внутри компонента.
  const [searchParams, setSearchParams] = useSearchParams();
  const stepParam = Number(searchParams.get('step'));
  const urlStep = Number.isInteger(stepParam) && stepParam >= 1 && stepParam <= TOTAL_STEPS ? stepParam : 1;
  const [name, setName] = useState<string | null>(null);
  const [city, setCity] = useState<CityDto | null>(null);
  const [description, setDescription] = useState<string | null>(null);
  const [interests, setInterests] = useState<string[] | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

  // Вернулся в приложение — продолжает с того шага, где остановился. URL при новом запуске
  // пустой, поэтому прогресс держим в localStorage и подставляем один раз, replace'ом:
  // в историю такой шаг попасть не должен, иначе «назад» уводило бы на него же.
  useEffect(() => {
    if (!id || searchParams.get('step')) return;
    const saved = readSavedStep(id);
    if (saved > 1) setSearchParams({ step: String(saved) }, { replace: true });
  }, [id, searchParams, setSearchParams]);

  useEffect(() => {
    if (id) localStorage.setItem(progressKey(id), String(urlStep));
  }, [id, urlStep]);

  if (clubQuery.isPending) return null;
  if (!club) return null;

  const nameValue = name ?? club.name;
  const descriptionValue = description ?? club.description;
  const interestsValue = interests ?? club.interests;
  const cityLabel = city?.name ?? (club.cityId ? club.city : null);
  const openClub = () => navigate(`/clubs/${club.id}`, { replace: true });
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

  // На первом шаге истории позади может не быть (сюда приходят редиректом), поэтому
  // «назад» уводит в клуб, а не закрывает приложение, как делает Layout по умолчанию.
  useBackButton(true, openClub);

  const saveError = updateClub.isError && (
    <p className="rd-wz-err" role="alert">Не получилось сохранить. Попробуйте ещё раз.</p>
  );

  return (
    <div className="rd-page rd-wz">
      <div className="rd-wz-bar">
        {Array.from({ length: TOTAL_STEPS }, (_, i) => (
          <span
            key={i}
            className={i + 1 === step ? 'rd-wz-seg rd-on' : i + 1 < step ? 'rd-wz-seg rd-done' : 'rd-wz-seg'}
          />
        ))}
      </div>
      <div className="rd-wz-cap">Шаг {step} из {TOTAL_STEPS}</div>

      {step === 1 && (
        <>
          <h1 className="rd-wz-q">Как назовём клуб?</h1>
          <p className="rd-wz-qsub">Взяли название чата — поменяйте, если хочется.</p>

          <div className="rd-wz-lbl">Название</div>
          <input
            className="rd-input"
            value={nameValue}
            maxLength={NAME_MAX}
            onChange={(e) => setName(e.target.value)}
            aria-label="Название клуба"
          />

          <div className="rd-wz-lbl">Аватар</div>
          <div className="rd-wz-ava-row">
            <ClubAvatarButton clubId={club.id} clubName={nameValue} avatarUrl={club.avatarUrl} editable />
            <span className="rd-wz-hint">Кружок клуба. Видно в списках и в шапке.</span>
          </div>

          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={!nameValue.trim() || updateClub.isPending}
            onClick={() => saveAndNext({ name: nameValue.trim() }, 2)}
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

          <button type="button" className="rd-btn-primary rd-wz-next" onClick={openClub}>
            Готово
          </button>
          <button type="button" className="rd-ghost-btn rd-wz-skip" onClick={openClub}>
            Пропустить обложку
          </button>
        </>
      )}
    </div>
  );
};
