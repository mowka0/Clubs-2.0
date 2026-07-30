import { FC, useRef, useState } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { uploadImage } from '../../api/clubs';
import { useUpdateClubMutation } from '../../queries/clubs';
import { validateImageFile } from '../../utils/imageUpload';

/** Камера в углу аватара — единственный намёк, что кружок кликабельный. */
const CameraIcon: FC = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
    <circle cx="12" cy="13" r="4" />
  </svg>
);

interface ClubAvatarButtonProps {
  clubId: string;
  /** Название клуба: из первой буквы собирается заглушка, когда картинки нет. */
  clubName: string;
  avatarUrl: string | null;
  /** Менеджеру аватар кликабелен (сменить картинку), остальным — просто картинка. */
  editable: boolean;
}

/**
 * Аватар клуба на стыке обложки и страницы. Менеджеру — точка входа в смену картинки:
 * тап открывает выбор файла, загрузка идёт в `/api/upload`, ссылка сохраняется через
 * `PUT /api/clubs/{id}`. Снять аватар можно в «Управление → Настройки» — здесь только
 * добавление и замена, чтобы тап по кружку не требовал промежуточного меню.
 */
export const ClubAvatarButton: FC<ClubAvatarButtonProps> = ({ clubId, clubName, avatarUrl, editable }) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const haptic = useHaptic();
  const updateClub = useUpdateClubMutation();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const face = avatarUrl
    ? <img src={avatarUrl} alt="" draggable={false} />
    : clubName.charAt(0).toUpperCase();

  if (!editable) {
    return (
      <div className="rd-club-avatar-wrap">
        <div className="rd-club-avatar">{face}</div>
      </div>
    );
  }

  const busy = uploading || updateClub.isPending;

  const pick = () => {
    if (busy) return;
    haptic.impact('light');
    inputRef.current?.click();
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Сбрасываем input сразу: иначе повторный выбор того же файла не даёт события change.
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
      const url = await uploadImage(file);
      // В запросе только аватар: по контракту бэкенда null-поля означают «оставить как есть»,
      // поэтому остальные настройки клуба тронуты не будут.
      await updateClub.mutateAsync({ id: clubId, body: { avatarUrl: url } });
      haptic.notify('success');
    } catch (err) {
      setError((err as Error).message || 'Не удалось сохранить аватар');
      haptic.notify('error');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="rd-club-avatar-wrap">
      <button
        type="button"
        className="rd-club-avatar rd-editable"
        onClick={pick}
        aria-label={avatarUrl ? 'Заменить аватар клуба' : 'Добавить аватар клуба'}
      >
        {face}
        <span className="rd-club-avatar-cam" aria-hidden="true"><CameraIcon /></span>
        {busy && (
          <span className="rd-club-avatar-busy" aria-hidden="true">
            <Spinner size="s" />
          </span>
        )}
      </button>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png"
        onChange={handleFile}
        data-testid="club-avatar-input"
        style={{ display: 'none' }}
      />
      {error && <div className="rd-club-avatar-err" role="alert">{error}</div>}
    </div>
  );
};
