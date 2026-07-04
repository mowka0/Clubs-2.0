import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useAuthStore } from '../../store/useAuthStore';
import { useUpdateProfileMutation } from '../../queries/profile';
import { CityPicker, countryNameByCode, type CityChoice } from '../CityPicker';
import { InterestsInput } from './InterestsInput';

// Максимальная длина поля «О себе» (символов) — совпадает с лимитом на бэкенде.
const BIO_MAX = 280;

interface ProfileEditModalProps {
  initialInterests: string[];
  onClose: () => void;
}

/**
 * Собственный portal-шит (не TGUI Modal): портал CityPicker живёт на z-index 200,
 * поэтому этот остаётся ниже (150), и пикер чисто открывается поверх.
 */
export const ProfileEditModal: FC<ProfileEditModalProps> = ({ initialInterests, onClose }) => {
  const haptic = useHaptic();
  const user = useAuthStore((s) => s.user);
  const updateMutation = useUpdateProfileMutation();

  const [cityChoice, setCityChoice] = useState<CityChoice>({
    country: user?.country ?? 'RU',
    city: user?.city ?? '',
  });
  const [bio, setBio] = useState(user?.bio ?? '');
  const [interests, setInterests] = useState<string[]>(initialInterests);
  const [cityPickerOpen, setCityPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  const hasCity = Boolean(cityChoice.city);
  const locationLabel = hasCity
    ? [cityChoice.city, countryNameByCode(cityChoice.country)].filter(Boolean).join(', ')
    : 'Не указан';

  const handleSave = () => {
    haptic.impact('medium');
    setError(null);
    updateMutation.mutate(
      {
        country: hasCity ? cityChoice.country : null,
        city: hasCity ? cityChoice.city : null,
        bio: bio.trim() || null,
        interests,
      },
      {
        onSuccess: () => { haptic.notify('success'); onClose(); },
        onError: (e) => { setError(e.message); haptic.notify('error'); },
      },
    );
  };

  const saving = updateMutation.isPending;

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Редактировать профиль">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Профиль</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="rd-sheet-body">
          <div className="rd-field">
            <span className="rd-label">Город</span>
            <button
              type="button"
              className="rd-input rd-field-btn"
              onClick={() => { haptic.select(); setCityPickerOpen(true); }}
            >
              <span className={hasCity ? '' : 'rd-placeholder'}>{locationLabel}</span>
              <span className="rd-chev" aria-hidden="true">›</span>
            </button>
          </div>

          <div className="rd-field">
            <span className="rd-label">О себе</span>
            <textarea
              className="rd-textarea"
              value={bio}
              maxLength={BIO_MAX}
              rows={3}
              onChange={(e) => setBio(e.target.value)}
              placeholder="Коротко о себе"
            />
            <div className="rd-hint" style={{ textAlign: 'right' }}>{bio.length}/{BIO_MAX}</div>
          </div>

          <div className="rd-field">
            <span className="rd-label">Интересы</span>
            <InterestsInput value={interests} onChange={setInterests} />
          </div>

          {error && <div className="rd-error" style={{ textAlign: 'left' }}>{error}</div>}
        </div>

        <div className="rd-sheet-actions">
          <button type="button" className="rd-btn-outline" onClick={onClose} disabled={saving}>Отмена</button>
          <button type="button" className="rd-btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? <Spinner size="s" /> : 'Сохранить'}
          </button>
        </div>
      </div>

      {cityPickerOpen && (
        <CityPicker
          value={cityChoice}
          onChange={setCityChoice}
          onClose={() => setCityPickerOpen(false)}
        />
      )}
    </>,
    document.body,
  );
};
