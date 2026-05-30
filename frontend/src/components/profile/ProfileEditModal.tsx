import { FC, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useAuthStore } from '../../store/useAuthStore';
import { useUpdateProfileMutation } from '../../queries/profile';
import { CityPicker, countryNameByCode, type CityChoice } from '../CityPicker';
import { InterestsInput } from './InterestsInput';

const BIO_MAX = 280;

interface ProfileEditModalProps {
  initialInterests: string[];
  onClose: () => void;
}

/**
 * Own portal sheet (not a TGUI Modal): the CityPicker portal sits at z-index
 * 200, so this stays below it (150) and the picker opens cleanly on top.
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
      <div className="pf-edit-overlay" onClick={onClose} aria-hidden="true" />
      <div className="pf-edit-sheet" role="dialog" aria-modal="true" aria-label="Редактировать профиль">
        <div className="city-picker-grabber" aria-hidden="true" />
        <div className="pf-edit-header">
          <h2>Профиль</h2>
          <button type="button" className="city-picker-close" onClick={onClose}>Закрыть</button>
        </div>

        <div className="pf-edit-body">
          <label className="pf-edit-label">Город</label>
          <button
            type="button"
            className="pf-edit-field"
            onClick={() => { haptic.select(); setCityPickerOpen(true); }}
          >
            <span className={hasCity ? '' : 'placeholder'}>{locationLabel}</span>
            <span className="chevron" aria-hidden="true">›</span>
          </button>

          <label className="pf-edit-label">О себе</label>
          <textarea
            className="pf-edit-textarea"
            value={bio}
            maxLength={BIO_MAX}
            rows={3}
            onChange={(e) => setBio(e.target.value)}
            placeholder="Коротко о себе"
          />
          <div className="pf-edit-counter">{bio.length}/{BIO_MAX}</div>

          <label className="pf-edit-label">Интересы</label>
          <InterestsInput value={interests} onChange={setInterests} />

          {error && <div className="pf-edit-error">{error}</div>}
        </div>

        <div className="pf-edit-actions">
          <button type="button" className="ghost-btn" onClick={onClose} disabled={saving}>Отмена</button>
          <button type="button" className="mc-create-btn" onClick={handleSave} disabled={saving}>
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
