import { FC } from 'react';
import type { ActivityType } from '../../api/activities';

interface ActivityThumbProps {
  type: ActivityType;
  photoUrl: string | null;
}

const TYPE_EMOJI: Record<ActivityType, string> = {
  event: '🗓',
  skladchina: '💰',
};

/**
 * Square thumbnail shown on the left of a full ActivityCard.
 * Renders the activity photo (cover) when present, otherwise a brass-tinted
 * placeholder tile with the type emoji centered.
 */
export const ActivityThumb: FC<ActivityThumbProps> = ({ type, photoUrl }) => {
  if (photoUrl) {
    return (
      <span className="activity-thumb">
        <img src={photoUrl} alt="" loading="lazy" />
      </span>
    );
  }

  return (
    <span className="activity-thumb placeholder" aria-hidden="true">
      <span className="placeholder-emoji">{TYPE_EMOJI[type]}</span>
    </span>
  );
};
