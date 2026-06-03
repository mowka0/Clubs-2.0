import { FC, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { ActivityCard } from './ActivityCard';
import { ActivityCompactRow } from './ActivityCompactRow';
import type { ActivityItemDto, ClubActivityFeed } from '../../api/activities';

interface ActivityFeedListProps {
  feed: ClubActivityFeed;
  onActivityClick: (activity: ActivityItemDto) => void;
}

export const ActivityFeedList: FC<ActivityFeedListProps> = ({
  feed,
  onActivityClick,
}) => {
  const haptic = useHaptic();
  const [pastExpanded, setPastExpanded] = useState(false);

  const togglePast = () => {
    haptic.impact('light');
    setPastExpanded((prev) => !prev);
  };

  return (
    <>
      {feed.upcoming.length > 0 && (
        <section>
          <div className="rd-section-sub-h">Предстоящие</div>
          {feed.upcoming.map((item) => (
            <ActivityCard
              key={item.id}
              activity={item}
              onClick={() => onActivityClick(item)}
            />
          ))}
        </section>
      )}

      {feed.past.length > 0 && (
        <section>
          <button
            type="button"
            className="rd-section-sub-h"
            aria-expanded={pastExpanded}
            onClick={togglePast}
            style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 0, cursor: 'pointer', width: '100%' }}
          >
            <span aria-hidden="true">{pastExpanded ? '▾' : '▸'}</span>
            Прошедшие
            <span className="rd-count">({feed.past.length})</span>
          </button>
          {pastExpanded && (
            <div className="rd-glass rd-rep-panel">
              {feed.past.map((item) => (
                <ActivityCompactRow
                  key={item.id}
                  activity={item}
                  onClick={() => onActivityClick(item)}
                />
              ))}
            </div>
          )}
        </section>
      )}
    </>
  );
};
