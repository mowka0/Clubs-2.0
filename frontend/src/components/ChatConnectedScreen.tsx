import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { CityPicker } from './CityPicker';
import { useHaptic } from '../hooks/useHaptic';
import { useUpdateClubMutation } from '../queries/clubs';
import type { CityDto, ClubDetailDto } from '../types/api';

interface ChatConnectedScreenProps {
  club: ClubDetailDto;
}

/**
 * Экран после подключения чата: подтверждение, что всё срослось, и единственный вопрос — город.
 *
 * Зачем город спрашивать здесь, а не в настройках клуба: без него не работает недельный опрос
 * («что делаем в выходные» подбирается по городу), а в чате его взять неоткуда — Telegram
 * города группы не сообщает. Момент выбран сознательно: человек только что увидел «получилось»,
 * и это самая дешёвая точка, где он готов ответить на один вопрос.
 *
 * Показывается, пока у клуба нет города (`cityId === null`) — см. HomeRoute.
 */
export const ChatConnectedScreen: FC<ChatConnectedScreenProps> = ({ club }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const [pickerOpen, setPickerOpen] = useState(false);
  const [city, setCity] = useState<CityDto | null>(null);
  const updateClub = useUpdateClubMutation();

  const openClub = () => navigate(`/clubs/${club.id}`, { replace: true });

  const save = () => {
    if (!city) return;
    haptic.impact('medium');
    updateClub.mutate(
      { id: club.id, body: { cityId: city.id } },
      { onSuccess: openClub },
    );
  };

  return (
    <div className="rd-page">
      <div className="rd-glass" style={{ padding: 16, marginBottom: 14 }}>
        <div className="rd-chip rd-chip-live" style={{ marginBottom: 10 }}>Чат подключён</div>
        <div className="rd-section-h" style={{ marginTop: 0 }}>{club.name}</div>
        <p className="rd-sub" style={{ margin: '6px 0 0' }}>
          Клуб создан из чата. Участники появятся здесь сами, когда начнут отвечать боту —
          список группы Telegram нам не отдаёт.
        </p>
      </div>

      <div className="rd-glass" style={{ padding: 16 }}>
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

        {/* Пропустить можно: город спрашивается ещё раз при следующем открытии, а держать
            человека на экране сразу после подключения — верный способ его потерять. */}
        <button
          type="button"
          className="rd-ghost-btn"
          style={{ width: '100%', marginTop: 8 }}
          onClick={openClub}
        >
          Позже
        </button>
      </div>

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
