import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useAuthStore } from '../../store/useAuthStore';
import { useUpdateProfileMutation } from '../../queries/profile';
import { CityPicker, countryNameByCode } from '../CityPicker';
import { useCities } from '../../queries/cities';
import type { CityDto } from '../../types/api';
import { InterestsInput, type InterestsInputHandle } from './InterestsInput';
import type { QuestStepKey } from './ProfileQuestCard';

// Максимальная длина поля «О себе» (символов) — совпадает с лимитом на бэкенде.
const BIO_MAX = 280;

interface ProfileEditModalProps {
  initialInterests: string[];
  /**
   * Вход из шага профиль-квеста: это поле подсвечивается пульсом, остальные притеняются
   * (мокап 04-quest-carousel, кадр D). null/не задано — обычный редактор без акцентов.
   */
  highlightField?: QuestStepKey | null;
  onClose: () => void;
}

/** Классы поля с учётом подсветки квеста: целевое — пульс, прочие — притенены. */
function fieldClass(field: QuestStepKey, highlight: QuestStepKey | null | undefined): string {
  if (!highlight) return 'rd-field';
  return highlight === field ? 'rd-field rd-field-hl' : 'rd-field rd-field-dim';
}

/**
 * Собственный portal-шит (не TGUI Modal): портал CityPicker живёт на z-index 200,
 * поэтому этот остаётся ниже (150), и пикер чисто открывается поверх.
 */
export const ProfileEditModal: FC<ProfileEditModalProps> = ({ initialInterests, highlightField = null, onClose }) => {
  const haptic = useHaptic();
  const user = useAuthStore((s) => s.user);
  const updateMutation = useUpdateProfileMutation();

  // Город хранится идентификатором справочника; отображаемое имя берётся из загруженного списка,
  // а не из профиля — так подпись не разъедется с выбором.
  const { data: cities } = useCities();
  const [cityId, setCityId] = useState<string | null>(user?.cityId ?? null);
  const city = cityId ? cities?.find((c) => c.id === cityId) ?? null : null;
  const [bio, setBio] = useState(user?.bio ?? '');
  const [interests, setInterests] = useState<string[]>(initialInterests);
  const interestsRef = useRef<InterestsInputHandle>(null);
  const bioRef = useRef<HTMLTextAreaElement>(null);
  const [cityPickerOpen, setCityPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  // Вход из шага «О себе»: подсвеченное поле сразу в фокусе — человек пишет, а не ищет.
  // Городу фокус не нужен (там пикер по тапу), интересам — свой инпут внутри компонента.
  useEffect(() => {
    if (highlightField === 'bio') bioRef.current?.focus();
  }, [highlightField]);

  // Пока справочник грузится, показываем сохранённое в профиле имя — иначе поле мигало бы
  // «Не указан» у человека, который город давно выбрал.
  const locationLabel = city
    ? [city.name, countryNameByCode(city.countryCode)].filter(Boolean).join(', ')
    : (cityId && user?.city ? user.city : 'Не указан');
  const hasCity = Boolean(cityId);

  const handleSave = () => {
    haptic.impact('medium');
    setError(null);
    // Недобранный текст в поле интересов (без запятой/Enter) фиксируем прямо здесь —
    // иначе первый интерес нового пользователя молча терялся при «Сохранить».
    const finalInterests = interestsRef.current?.commitPending() ?? interests;
    updateMutation.mutate(
      {
        cityId,
        bio: bio.trim() || null,
        interests: finalInterests,
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
          <div className={fieldClass('city', highlightField)}>
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

          <div className={fieldClass('bio', highlightField)}>
            <span className="rd-label">О себе</span>
            <textarea
              ref={bioRef}
              className="rd-textarea"
              value={bio}
              maxLength={BIO_MAX}
              rows={3}
              onChange={(e) => setBio(e.target.value)}
              placeholder="Чем увлекаешься?)"
            />
            <div className="rd-hint" style={{ textAlign: 'right' }}>{bio.length}/{BIO_MAX}</div>
          </div>

          <div className={fieldClass('interests', highlightField)}>
            <span className="rd-label">Интересы</span>
            <InterestsInput ref={interestsRef} value={interests} onChange={setInterests} />
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
          value={city}
          onChange={(next: CityDto) => setCityId(next.id)}
          onClose={() => setCityPickerOpen(false)}
        />
      )}
    </>,
    document.body,
  );
};
