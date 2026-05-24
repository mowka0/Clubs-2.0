import { FC } from 'react';
import type { ClubDetailDto } from '../../types/api';

function getClubInitials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

interface ManageHeaderProps {
  club: ClubDetailDto;
  onOpenClub: () => void;
}

/**
 * Brand hero for the organizer Manage screen. Clickable — routes back to the
 * public ClubPage. Mirrors the `mc-hero` language used on MyClubsPage while
 * carrying a small club avatar like ClubPage's cover.
 */
export const ManageHeader: FC<ManageHeaderProps> = ({ club, onOpenClub }) => (
  <button type="button" className="manage-hero" onClick={onOpenClub}>
    <span className="manage-hero-avt" data-cat={club.category}>
      {club.avatarUrl ? <img src={club.avatarUrl} alt="" /> : getClubInitials(club.name)}
    </span>
    <div className="manage-hero-body">
      <h1 className="manage-hero-title">{club.name}</h1>
      <div className="manage-hero-sub">
        {club.memberCount} / {club.memberLimit} участников · {club.city}
      </div>
    </div>
    <span className="manage-hero-chevron" aria-hidden="true">
      ›
    </span>
  </button>
);
