import { FC, useState } from 'react';
import { CityPicker } from '../../CityPicker';
import { useHaptic } from '../../../hooks/useHaptic';
import type { ClubSetupStepProps } from './types';

type ClubSetupCityStepProps = ClubSetupStepProps;

/**
 * Шаг 2: город. Единственный шаг, который нельзя пропустить, — без города не работает
 * недельный опрос, ради которого клуб и подключают к чату (решение PO спринта 1.0).
 */
export const ClubSetupCityStep: FC<ClubSetupCityStepProps> = ({
  club,
  draft,
  onDraftChange,
  saving,
  error,
  onSaveAndNext,
  onGoToStep,
}) => {
  const haptic = useHaptic();
  const [pickerOpen, setPickerOpen] = useState(false);
  const city = draft.city;
  const cityLabel = city?.name ?? (club.cityId ? club.city : null);

  return (
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

      {/* Кнопка живёт и с уже сохранённым городом: вернувшись на этот шаг, человек видел
          свой город в поле и мёртвую кнопку — локальный выбор был пуст (баг PO 2026-08-19).
          Города не меняли — просто идём дальше, не трогая сервер. */}
      <button
        type="button"
        className="rd-btn-primary rd-wz-next"
        disabled={(!city && !club.cityId) || saving}
        onClick={() => {
          if (city) onSaveAndNext({ cityId: city.id }, 3);
          else if (club.cityId) { haptic.impact('light'); onGoToStep(3); }
        }}
      >
        Дальше
      </button>
      {error}
      <div className="rd-wz-note">Пропустить нельзя — это единственное исключение</div>

      {pickerOpen && (
        <CityPicker
          value={city}
          onChange={(next) => { onDraftChange({ city: next }); setPickerOpen(false); }}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </>
  );
};
