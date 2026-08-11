import { FC } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClubImageUpload } from '../../hooks/useClubImageUpload';
import { IMAGE_ACCEPT_ATTR } from '../../utils/imageUpload';

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
 * тап открывает выбор файла, загрузка идёт в `/api/upload`, ссылка сохраняется в поле
 * `avatarUrl` (обложка живёт в отдельном `coverUrl` и не меняется). Снять аватар можно
 * в «Управление → Настройки» — здесь только добавление и замена, чтобы тап по кружку
 * не требовал промежуточного меню.
 */
export const ClubAvatarButton: FC<ClubAvatarButtonProps> = ({ clubId, clubName, avatarUrl, editable }) => {
  const { inputRef, pick, handleFile, busy, error } = useClubImageUpload(clubId, 'avatarUrl');

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
        accept={IMAGE_ACCEPT_ATTR}
        onChange={handleFile}
        data-testid="club-avatar-input"
        style={{ display: 'none' }}
      />
      {error && <div className="rd-club-avatar-err" role="alert">{error}</div>}
    </div>
  );
};
