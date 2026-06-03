import { FC } from 'react';
import type { ClubDetailDto } from '../../types/api';

interface ManageHeaderProps {
  club: ClubDetailDto;
  onOpenClub: () => void;
}

/**
 * Full-bleed `rd-hero` for the organizer Manage screen. The whole hero is
 * clickable — tapping routes back to the public ClubPage (chevron hints at it).
 * Mirrors the hero language used on ClubPage / EventPage.
 */
export const ManageHeader: FC<ManageHeaderProps> = ({ club, onOpenClub }) => (
  <button
    type="button"
    className="rd-hero rd-compact"
    onClick={onOpenClub}
    style={{ display: 'block', padding: 0, border: 0, background: 'transparent', font: 'inherit', textAlign: 'left', cursor: 'pointer' }}
  >
    <div
      className="rd-hero-bg"
      data-cat={club.category}
      style={club.avatarUrl ? { backgroundImage: `url(${club.avatarUrl})` } : undefined}
    />
    <span className="rd-hero-btn rd-right" aria-hidden="true" style={{ fontSize: 22, lineHeight: 1 }}>
      ›
    </span>
    <div className="rd-hero-meta">
      <div className="rd-hero-type-badge">УПРАВЛЕНИЕ</div>
      <div className="rd-hero-ttl">{club.name}</div>
      <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
        {club.memberCount} / {club.memberLimit} участников · {club.city}
      </div>
    </div>
  </button>
);
