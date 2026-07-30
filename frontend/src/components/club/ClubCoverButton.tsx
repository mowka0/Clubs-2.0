import { FC } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClubImageUpload } from '../../hooks/useClubImageUpload';

/** Та же камера, что у аватара, — приём один: картинку меняют тапом по ней самой. */
const CameraIcon: FC = () => (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
    <circle cx="12" cy="13" r="4" />
  </svg>
);

interface ClubCoverButtonProps {
  clubId: string;
  hasCover: boolean;
}

/**
 * Смена обложки клуба — круглая кнопка в паре с шестерёнкой в правом верхнем углу обложки.
 * Пишет в `coverUrl`; аватар (`avatarUrl`) при этом не трогается — до V70 это была одна
 * картинка, и смена аватара молча меняла обложку.
 *
 * Сообщение об ошибке рендерит родитель через `error`, потому что место под него — низ
 * обложки, а не сама кнопка.
 */
export const ClubCoverButton: FC<ClubCoverButtonProps> = ({ clubId, hasCover }) => {
  const { inputRef, pick, handleFile, busy, error } = useClubImageUpload(clubId, 'coverUrl');

  return (
    <>
      <button
        type="button"
        className="rd-hero-btn"
        onClick={pick}
        aria-label={hasCover ? 'Заменить обложку клуба' : 'Добавить обложку клуба'}
        title={hasCover ? 'Заменить обложку' : 'Добавить обложку'}
      >
        {busy ? <Spinner size="s" /> : <CameraIcon />}
      </button>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png"
        onChange={handleFile}
        data-testid="club-cover-input"
        style={{ display: 'none' }}
      />
      {error && <div className="rd-hero-err" role="alert">{error}</div>}
    </>
  );
};
