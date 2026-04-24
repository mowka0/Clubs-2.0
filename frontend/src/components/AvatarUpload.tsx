import { FC, useRef, useState } from 'react';
import { Button, Spinner, Text } from '@telegram-apps/telegram-ui';
import { uploadImage } from '../api/clubs';

const MAX_BYTES = 5 * 1024 * 1024;
const ALLOWED_MIMES = new Set(['image/jpeg', 'image/png']);

interface Props {
  value: string | null;
  onChange: (url: string | null) => void;
  disabled?: boolean;
}

export const AvatarUpload: FC<Props> = ({ value, onChange, disabled }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = () => inputRef.current?.click();

  const handleChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    setError(null);
    if (!ALLOWED_MIMES.has(file.type)) {
      setError('Только JPEG и PNG');
      return;
    }
    if (file.size > MAX_BYTES) {
      setError('Файл больше 5 МБ');
      return;
    }

    setUploading(true);
    try {
      const url = await uploadImage(file);
      onChange(url);
    } catch (err) {
      setError((err as Error).message || 'Не удалось загрузить файл');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
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
        accept="image/jpeg,image/png"
        onChange={handleChange}
        style={{ display: 'none' }}
      />

      <div style={{ display: 'flex', gap: 8 }}>
        <Button size="s" mode="outline" onClick={pick} disabled={disabled || uploading}>
          {value ? 'Заменить' : 'Загрузить'}
        </Button>
        {value && (
          <Button
            size="s"
            mode="plain"
            onClick={() => {
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
        <Text style={{ fontSize: 12, color: 'var(--tgui--destructive_text_color, #d00)' }}>{error}</Text>
      )}
    </div>
  );
};
