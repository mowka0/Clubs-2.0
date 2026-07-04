import { FC, useRef, useState } from 'react';
import { uploadImage } from '../api/clubs';
import { useHaptic } from '../hooks/useHaptic';
import { ImageLightbox } from './ImageLightbox';

// Максимальный размер файла — 5 МБ, зеркалит лимит backend /api/upload.
const MAX_BYTES = 5 * 1024 * 1024;
// Разрешённые MIME-типы загружаемого фото — те же, что валидирует backend /api/upload.
const ALLOWED_MIMES = new Set(['image/jpeg', 'image/png']);

interface Props {
  value: string | null;
  onChange: (url: string | null) => void;
  disabled?: boolean;
  /** Текст кнопки, пока ничего не прикреплено, например "Прикрепить чек". */
  addLabel?: string;
}

/**
 * Прикрепление одного фото (чека) к форме: загрузка → превью → тап-на-весь-экран. Широкое превью
 * (не обрезка под аватар) сохраняет чек читаемым, а [ImageLightbox] открывает его на весь экран,
 * чтобы каждый участник мог разобрать свою долю. Валидация загрузки зеркалит backend /api/upload
 * (JPEG/PNG, ≤5 МБ). Переиспользуется в формах создания складчины.
 */
export const PhotoAttach: FC<Props> = ({ value, onChange, disabled, addLabel = 'Прикрепить фото' }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const haptic = useHaptic();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [zoomed, setZoomed] = useState(false);

  const pick = () => {
    if (disabled || uploading) return;
    haptic.impact('light');
    inputRef.current?.click();
  };

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    setError(null);
    if (!ALLOWED_MIMES.has(file.type)) {
      setError('Только JPEG и PNG');
      haptic.notify('error');
      return;
    }
    if (file.size > MAX_BYTES) {
      setError('Файл больше 5 МБ');
      haptic.notify('error');
      return;
    }

    setUploading(true);
    try {
      const url = await uploadImage(file);
      onChange(url);
      haptic.notify('success');
    } catch (err) {
      setError((err as Error).message || 'Не удалось загрузить файл');
      haptic.notify('error');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {value ? (
        <>
          <button
            type="button"
            onClick={() => { haptic.impact('light'); setZoomed(true); }}
            className="rd-glass"
            style={{ overflow: 'hidden', padding: 0, border: 'none', cursor: 'pointer', display: 'block' }}
          >
            <img
              src={value}
              alt="Фото сбора"
              style={{ width: '100%', maxHeight: 240, objectFit: 'cover', display: 'block' }}
            />
          </button>
          <span className="rd-hint">Нажмите на фото, чтобы открыть на весь экран</span>
          <div style={{ display: 'flex', gap: 12 }}>
            <button type="button" className="rd-btn-outline" onClick={pick} disabled={disabled || uploading}>
              {uploading ? 'Загрузка…' : 'Заменить'}
            </button>
            <button
              type="button"
              className="rd-btn-outline"
              onClick={() => { haptic.impact('light'); setError(null); onChange(null); }}
              disabled={disabled || uploading}
            >
              Убрать
            </button>
          </div>
        </>
      ) : (
        <button type="button" className="rd-btn-outline" onClick={pick} disabled={disabled || uploading}>
          {uploading ? 'Загрузка…' : `📎 ${addLabel}`}
        </button>
      )}

      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png"
        onChange={handleChange}
        style={{ display: 'none' }}
      />

      {error && <span className="rd-error">{error}</span>}

      <ImageLightbox src={zoomed ? value : null} onClose={() => setZoomed(false)} />
    </div>
  );
};
