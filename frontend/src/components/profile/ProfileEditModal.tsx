import { FC, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useSheetDrag } from '../../hooks/useSheetDrag';
import { useKeyboardAwareSheet } from '../../hooks/useKeyboardAwareSheet';
import { useAuthStore } from '../../store/useAuthStore';
import { useUpdateProfileMutation } from '../../queries/profile';
import { CityPicker, countryNameByCode } from '../CityPicker';
import { useCities } from '../../queries/cities';
import type { CityDto } from '../../types/api';
import { InterestsInput, type InterestsInputHandle } from './InterestsInput';
import { pluralRu } from '../../utils/formatters';
import { QuestCheck } from './ProfileQuestCard';

// Максимальная длина поля «О себе» (символов) — совпадает с лимитом на бэкенде.
const BIO_MAX = 280;
/**
 * Длина «о себе», с которой поле считается заполненным и загорается галочка. Парное значение
 * с `BIO_QUEST_MIN_LENGTH` на бэкенде: галочка обязана означать ровно то же, что начисленные XP.
 */
const BIO_QUEST_MIN = 10;

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

  // Город хранится идентификатором справочника; отображаемое имя берётся из загруженного списка,
  // а не из профиля — так подпись не разъедется с выбором.
  const { data: cities } = useCities();
  const [cityId, setCityId] = useState<string | null>(user?.cityId ?? null);
  const city = cityId ? cities?.find((c) => c.id === cityId) ?? null : null;
  const [bio, setBio] = useState(user?.bio ?? '');
  const [interests, setInterests] = useState<string[]>(initialInterests);
  const interestsRef = useRef<InterestsInputHandle>(null);
  const [cityPickerOpen, setCityPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Шторку закрывают протяжкой вниз за шапку; под клавиатурой она ужимается по видимой
  // области, а не по высоте окна, и доводит поле в фокусе до видимой зоны.
  const { sheetRef, dragHandlers } = useSheetDrag(onClose);
  useKeyboardAwareSheet(sheetRef);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  // Пока справочник грузится, показываем сохранённое в профиле имя — иначе поле мигало бы
  // «Не указан» у человека, который город давно выбрал.
  const locationLabel = city
    ? [city.name, countryNameByCode(city.countryCode)].filter(Boolean).join(', ')
    : (cityId && user?.city ? user.city : 'Не указан');
  const hasCity = Boolean(cityId);
  // Галочка «о себе» считается по тому же порогу, что веха квеста на бэке, и обновляется
  // прямо во время ввода: человек видит, в какой момент поле засчитано.
  const bioDone = bio.trim().length >= BIO_QUEST_MIN;
  const bioLeft = BIO_QUEST_MIN - bio.trim().length;

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
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet rd-profile-sheet rd-sheet-in" role="dialog" aria-modal="true" aria-label="Редактировать профиль" ref={sheetRef}>
        {/* Шапка-«ручка»: за неё шторку тянут вниз, чтобы закрыть. Тело со скроллом не трогаем. */}
        <div className="rd-sheet-grip" {...dragHandlers}>
          <div className="rd-sheet-grabber" aria-hidden="true" />
          <div className="rd-sheet-head">
            <h2>Профиль</h2>
            <button type="button" className="rd-sheet-close" onClick={onClose}>Закрыть</button>
          </div>
        </div>

        <div className="rd-sheet-body">
          <div className="rd-field">
            <span className="rd-label rd-label-check">
              <QuestCheck done={hasCity} />
              Город
            </span>
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
            <span className="rd-label rd-label-check">
              <QuestCheck done={bioDone} />
              О себе
            </span>
            <textarea
              className="rd-textarea"
              value={bio}
              maxLength={BIO_MAX}
              rows={3}
              onChange={(e) => setBio(e.target.value)}
              placeholder="Чем увлекаешься?)"
            />
            <div className="rd-hint rd-hint-row">
              <span>{bioDone ? '' : `ещё ${bioLeft} ${pluralRu(bioLeft, ['символ', 'символа', 'символов'])} до галочки`}</span>
              <span>{bio.length}/{BIO_MAX}</span>
            </div>
          </div>

          <div className="rd-field">
            <span className="rd-label rd-label-check">
              <QuestCheck done={interests.length > 0} />
              Интересы
            </span>
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
