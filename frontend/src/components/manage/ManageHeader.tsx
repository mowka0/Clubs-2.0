import { FC } from 'react';
import type { ClubDetailDto } from '../../types/api';

interface ManageHeaderProps {
  club: ClubDetailDto;
}

/**
 * Full-bleed `rd-hero` для экрана «Управление» организатора. Только отображение, без
 * собственных кнопок: назад ведут нативный BackButton Telegram и свайп от кромки, поэтому
 * своя кнопка «назад» на обложке была избыточной (решение PO 2026-07-30).
 */
export const ManageHeader: FC<ManageHeaderProps> = ({ club }) => (
  <div
    className="rd-hero rd-compact"
    style={{ width: 'calc(100% + 32px)' }}
  >
    <div
      className="rd-hero-bg"
      data-cat={club.category}
      style={club.avatarUrl ? { backgroundImage: `url(${club.avatarUrl})` } : undefined}
    />
    <div className="rd-hero-meta">
      <div className="rd-hero-ttl">{club.name}</div>
      <div className="rd-hero-eyebrow" style={{ marginTop: 6 }}>
        {club.memberCount} / {club.memberLimit} участников · {club.city}
      </div>
    </div>
  </div>
);
