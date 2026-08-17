import { FC, useState } from 'react';
import { CityPicker } from '../CityPicker';
import { useHaptic } from '../../hooks/useHaptic';
import { useUpdateClubMutation } from '../../queries/clubs';
import type { CityDto, ClubDetailDto } from '../../types/api';

interface ClubCityPromptProps {
  club: ClubDetailDto;
  /** Вызывается после успешного сохранения (на «/» — уводит в клуб). */
  onSaved?: () => void;
  /** Кнопка «Позже»: показывается только там, где есть куда уйти. */
  onSkip?: () => void;
}

/**
 * Карточка «В каком городе встречаетесь?» для клуба без города (`cityId === null`).
 *
 * Живёт в двух местах, потому что попасть в новый клуб можно двумя дверями: с корневого
 * экрана после возвращения из Telegram и по кнопке из закрепа в самом чате. Вторая дверь
 * первое время была слепой — человек видел голую страницу клуба без единой подсказки.
 *
 * Город нужен недельному опросу (форматы подбираются по городу), а взять его из чата
 * нельзя: Telegram города группы не сообщает.
 */
export const ClubCityPrompt: FC<ClubCityPromptProps> = ({ club, onSaved, onSkip }) => {
  const haptic = useHaptic();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [city, setCity] = useState<CityDto | null>(null);
  const updateClub = useUpdateClubMutation();

  const save = () => {
    if (!city) return;
    haptic.impact('medium');
    updateClub.mutate({ id: club.id, body: { cityId: city.id } }, { onSuccess: () => onSaved?.() });
  };

  return (
    <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
      <div className="rd-section-sub-h" style={{ marginTop: 0 }}>В каком городе встречаетесь?</div>
      <p className="rd-sub" style={{ margin: '6px 0 12px' }}>
        Нужно, чтобы бот предлагал встречи рядом с вами.
      </p>

      <button
        type="button"
        className="rd-input-like"
        onClick={() => { haptic.impact('light'); setPickerOpen(true); }}
      >
        {city ? city.name : 'Выбрать город'}
      </button>

      <button
        type="button"
        className="rd-btn-primary"
        style={{ width: '100%', marginTop: 12 }}
        disabled={!city || updateClub.isPending}
        onClick={save}
      >
        {updateClub.isPending ? 'Сохраняем…' : 'Готово'}
      </button>

      {updateClub.isError && (
        <p className="rd-sub" role="alert" style={{ margin: '10px 0 0', color: 'var(--danger)' }}>
          Не получилось сохранить город. Попробуйте ещё раз.
        </p>
      )}

      {onSkip && (
        <button
          type="button"
          className="rd-ghost-btn"
          style={{ width: '100%', marginTop: 8 }}
          onClick={onSkip}
        >
          Позже
        </button>
      )}

      {pickerOpen && (
        <CityPicker
          value={city}
          onChange={(next) => { setCity(next); setPickerOpen(false); }}
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  );
};
