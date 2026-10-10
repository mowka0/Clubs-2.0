import { FC, useRef, useState } from 'react';
import { Button, Spinner, Text } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { uploadImage } from '../api/clubs';
import { IMAGE_ACCEPT_ATTR, validateImageFile, type ImagePurpose } from '../utils/imageUpload';

interface Props {
  value: string | null;
  onChange: (url: string | null) => void;
  disabled?: boolean;
  /** Колонка общей карточки (аватар | обложка в настройках клуба): всё по центру, кнопки друг
      под другом — в половину ширины рядом они не помещаются. */
  centered?: boolean;
  /** До какого размера ужать перед отправкой: аватар меньше, обложка и фото — во всю ширину. */
  purpose?: ImagePurpose;
}

/** Кнопки в ряд — обычный вид; в колонку — `centered`, где ряд не помещается. */
const ROW_BUTTONS = { display: 'flex', gap: 8 } as const;
const STACKED_BUTTONS = { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 } as const;

export const AvatarUpload: FC<Props> = ({ value, onChange, disabled, centered = false, purpose = 'photo' }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const haptic = useHaptic();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = () => {
    haptic.impact('light');
    inputRef.current?.click();
  };

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    setError(null);
    const invalid = validateImageFile(file);
    if (invalid) {
      setError(invalid);
      haptic.notify('error');
      return;
    }

    setUploading(true);
    try {
      const url = await uploadImage(file, purpose);
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
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: centered ? 'center' : 'flex-start' }}>
      <div
        onClick={disabled || uploading ? undefined : pick}
        style={{
          width: 96,
          height: 96,
          borderRadius: 16,
          background: 'var(--tgui--secondary_bg_color)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'hidden',
          cursor: disabled || uploading ? 'default' : 'pointer',
          position: 'relative',
        }}
      >
        {value ? (
          <img src={value} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
        ) : (
          <span style={{ fontSize: 36, opacity: 0.5 }}>&#x1F4F7;</span>
        )}
        {uploading && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: 'rgba(0,0,0,0.5)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Spinner size="s" />
          </div>
        )}
      </div>

      <input
        ref={inputRef}
        type="file"
        accept={IMAGE_ACCEPT_ATTR}
        onChange={handleChange}
        style={{ display: 'none' }}
      />

      <div style={centered ? STACKED_BUTTONS : ROW_BUTTONS}>
        <Button size="s" mode="outline" onClick={pick} disabled={disabled || uploading}>
          {value ? 'Заменить' : 'Загрузить'}
        </Button>
        {value && (
          <Button
            size="s"
            mode="plain"
            onClick={() => {
              haptic.impact('light');
              setError(null);
              onChange(null);
            }}
            disabled={disabled || uploading}
          >
            Убрать
          </Button>
        )}
      </div>

      {error && (
        <Text style={{ fontSize: 12, color: 'var(--tgui--destructive_text_color, #d00)', textAlign: centered ? 'center' : undefined }}>{error}</Text>
      )}
    </div>
  );
};
