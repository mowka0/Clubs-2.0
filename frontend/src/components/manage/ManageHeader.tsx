import { FC } from 'react';
import type { ClubDetailDto } from '../../types/api';

interface ManageHeaderProps {
  club: ClubDetailDto;
  onBack: () => void;
}

const BackIcon: FC = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M15 18l-6-6 6-6" />
  </svg>
);

/**
 * Full-bleed `rd-hero` for the organizer Manage screen. Display-only — a
 * top-left back button returns to the public ClubPage.
 */
export const ManageHeader: FC<ManageHeaderProps> = ({ club, onBack }) => (
  <div
    className="rd-hero rd-compact"
    style={{ width: 'calc(100% + 32px)' }}
  >
    <div
      className="rd-hero-bg"
      data-cat={club.category}
      style={club.avatarUrl ? { backgroundImage: `url(${club.avatarUrl})` } : undefined}
    />
    <button
      type="button"
      className="rd-hero-btn rd-left"
      onClick={onBack}
      aria-label="Назад к клубу"
    >
      <BackIcon />
    </button>
    <div className="rd-hero-meta">
      <div className="rd-hero-type-badge">УПРАВЛЕНИЕ</div>
      <div className="rd-hero-ttl">{club.name}</div>
      <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
        {club.memberCount} / {club.memberLimit} участников · {club.city}
      </div>
    </div>
  </div>
);
