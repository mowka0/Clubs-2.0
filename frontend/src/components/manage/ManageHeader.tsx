import { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../../hooks/useHaptic';
import { useHistoryPosition } from '../../hooks/useHistoryPosition';
import { memberCountCaption } from '../../utils/formatters';
import type { ClubDetailDto } from '../../types/api';

interface ManageHeaderProps {
  club: ClubDetailDto;
}

/**
 * Full-bleed `rd-hero` для экрана «Управление» организатора: название и состав слева, аватар
 * клуба справа. Аватар — второй путь назад на страницу клуба (PO 2026-10-08): нативную кнопку
 * Telegram и свайп от кромки находят не все, а своя стрелка на обложке была лишней (PO 2026-07-30).
 */
export const ManageHeader: FC<ManageHeaderProps> = ({ club }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const { canGoBack } = useHistoryPosition();

  // Страница клуба обычно лежит прямо под «Управлением» (шестерёнка, баннер чата,
  // `useClubPageUnderneath`) — возвращаемся к ней, не плодя записей в истории.
  const openClub = () => {
    haptic.impact('light');
    if (canGoBack()) navigate(-1);
    else navigate(`/clubs/${club.id}`);
  };

  return (
    <div
      className="rd-hero rd-compact rd-manage-hero"
      style={{ width: 'calc(100% + 32px)' }}
    >
      <div
        className="rd-hero-bg"
        data-cat={club.category}
        style={club.coverUrl ? { backgroundImage: `url(${club.coverUrl})` } : undefined}
      />
      <div className="rd-hero-meta">
        <div className="rd-hero-ttl">{club.name}</div>
        <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
          {memberCountCaption(club.memberCount)} · {club.city}
        </div>
      </div>
      <button
        type="button"
        className="rd-club-avatar rd-manage-hero-ava"
        onClick={openClub}
        aria-label="Открыть страницу клуба"
      >
        {club.avatarUrl
          ? <img src={club.avatarUrl} alt="" draggable={false} />
          : club.name.charAt(0).toUpperCase()}
      </button>
    </div>
  );
};
