import { FC, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { CityPicker } from '../components/CityPicker';
import { ClubAvatarButton } from '../components/club/ClubAvatarButton';
import { ClubCoverButton } from '../components/club/ClubCoverButton';
import { useHaptic } from '../hooks/useHaptic';
import { useClubQuery, useUpdateClubMutation } from '../queries/clubs';
import type { CityDto } from '../types/api';

/** Сколько шагов в мастере — от него считается полоска прогресса. */
const TOTAL_STEPS = 4;
/** Потолок названия клуба, совпадает с VARCHAR(60) в схеме. */
const NAME_MAX = 60;
/** Потолок описания, совпадает с VARCHAR(500). */
const DESCRIPTION_MAX = 500;

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
 */
export const ClubSetupWizard: FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const haptic = useHaptic();
  const clubQuery = useClubQuery(id);
  const updateClub = useUpdateClubMutation();

  const club = clubQuery.data;
  const [step, setStep] = useState(1);
  const [name, setName] = useState<string | null>(null);
  const [city, setCity] = useState<CityDto | null>(null);
  const [description, setDescription] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);

  if (clubQuery.isPending) return null;
  if (!club) return null;

  const nameValue = name ?? club.name;
  const descriptionValue = description ?? club.description;
  const openClub = () => navigate(`/clubs/${club.id}`, { replace: true });

  /**
   * Сохраняем на каждом переходе, а не одной пачкой в конце: человек может закрыть приложение
   * на любом шаге, и уже введённое должно остаться. Ошибку показываем, но с шага не уводим.
   */
  const saveAndNext = (body: Record<string, unknown>, next: number) => {
    haptic.impact('light');
    updateClub.mutate(
      { id: club.id, body },
      { onSuccess: () => (next > TOTAL_STEPS ? openClub() : setStep(next)) },
    );
  };

  const progress = (
    <>
      <div className="rd-wz-bar">
        {Array.from({ length: TOTAL_STEPS }, (_, i) => (
          <span key={i} className={i + 1 === step ? 'rd-wz-seg rd-on' : i + 1 < step ? 'rd-wz-seg rd-done' : 'rd-wz-seg'} />
        ))}
      </div>
      <div className="rd-wz-cap">Шаг {step} из {TOTAL_STEPS}</div>
    </>
  );

  const saveError = updateClub.isError && (
    <p className="rd-sub" role="alert" style={{ margin: '10px 0 0', color: 'var(--danger)' }}>
      Не получилось сохранить. Попробуйте ещё раз.
    </p>
  );

  return (
    <div className="rd-page">
      {progress}

      {step === 1 && (
        <>
          <div className="rd-section-h" style={{ marginTop: 0 }}>Как назовём клуб?</div>
          <p className="rd-sub">Взяли название чата — поменяйте, если хочется.</p>

          <input
            className="rd-input"
            value={nameValue}
            maxLength={NAME_MAX}
            onChange={(e) => setName(e.target.value)}
            aria-label="Название клуба"
          />

          <div className="rd-wz-avatar">
            <ClubAvatarButton
              clubId={club.id}
              clubName={nameValue}
              avatarUrl={club.avatarUrl}
              editable
            />
            <span className="rd-sub">Кружок клуба — видно в списках и в шапке.</span>
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
        </>
      )}

      {step === 2 && (
        <>
          <div className="rd-section-h" style={{ marginTop: 0 }}>В каком городе встречаетесь?</div>
          <p className="rd-sub">Без города бот не сможет предлагать встречи рядом.</p>

          <button type="button" className="rd-input-like" onClick={() => { haptic.impact('light'); setPickerOpen(true); }}>
            {city ? city.name : 'Выбрать город'}
          </button>

          {/* Единственный шаг без «Пропустить»: город — условие работы недельного опроса. */}
          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={!city || updateClub.isPending}
            onClick={() => city && saveAndNext({ cityId: city.id }, 3)}
          >
            Дальше
          </button>
          {saveError}

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
          <div className="rd-section-h" style={{ marginTop: 0 }}>О чём ваш клуб?</div>
          <p className="rd-sub">Это первое, что прочитают участники, открыв страницу.</p>

          <textarea
            className="rd-input rd-wz-area"
            value={descriptionValue}
            maxLength={DESCRIPTION_MAX}
            onChange={(e) => setDescription(e.target.value)}
            aria-label="Описание клуба"
          />

          <button
            type="button"
            className="rd-btn-primary rd-wz-next"
            disabled={updateClub.isPending}
            onClick={() => saveAndNext({ description: descriptionValue.trim() }, 4)}
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
          <div className="rd-section-h" style={{ marginTop: 0 }}>Обложка клуба</div>
          <p className="rd-sub">Так страницу увидят участники.</p>

          <div className="rd-wz-cover">
            <ClubCoverButton clubId={club.id} hasCover={!!club.coverUrl} />
          </div>

          {/* Обложка сохраняется своей кнопкой сразу, поэтому «Готово» здесь только уводит. */}
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
