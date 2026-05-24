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
          <div className="activity-section-label">Предстоящие</div>
          <div className="activity-list">
            {feed.upcoming.map((item) => (
              <ActivityCard
                key={item.id}
                activity={item}
                onClick={() => onActivityClick(item)}
              />
            ))}
          </div>
        </section>
      )}

      {feed.past.length > 0 && (
        <section>
          <button
            type="button"
            className="activity-past-toggle"
            aria-expanded={pastExpanded}
            onClick={togglePast}
          >
            <span className="chevron" aria-hidden="true">
              {pastExpanded ? '▾' : '▸'}
            </span>
            <span className="label">Прошедшие</span>
            <span className="count">({feed.past.length})</span>
          </button>
          {pastExpanded && (
            <div className="activity-list compact">
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
