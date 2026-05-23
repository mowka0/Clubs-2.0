import { FC, useEffect, useMemo, useRef } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { ActivityCard } from './ActivityCard';
import { groupActivitiesByDay } from '../../utils/activityGrouping';
import type { ActivityItemDto } from '../../api/activities';

interface ActivityFeedListProps {
  activities: ActivityItemDto[];
  onActivityClick: (activity: ActivityItemDto) => void;
  loadMore?: () => void;
  hasMore?: boolean;
  isLoadingMore?: boolean;
}

const dayLabelStyle: React.CSSProperties = {
  padding: '12px 16px 6px',
  fontSize: 12,
  fontWeight: 600,
  letterSpacing: 0.6,
  color: 'var(--tgui--hint_color, rgba(255,255,255,0.55))',
  textTransform: 'uppercase',
};

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  padding: '0 16px',
};

const sentinelStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'center',
  padding: 16,
};

export const ActivityFeedList: FC<ActivityFeedListProps> = ({
  activities,
  onActivityClick,
  loadMore,
  hasMore,
  isLoadingMore,
}) => {
  const groups = useMemo(() => groupActivitiesByDay(activities), [activities]);
  const sentinelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore || isLoadingMore || !loadMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) loadMore();
      },
      { rootMargin: '200px' },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasMore, isLoadingMore, loadMore]);

  return (
    <>
      {groups.map((group) => (
        <section key={group.dayLabel + '|' + group.items[0]?.id}>
          <div style={dayLabelStyle}>{group.dayLabel}</div>
          <div style={listStyle}>
            {group.items.map((item) => (
              <ActivityCard
                key={item.id}
                activity={item}
                onClick={() => onActivityClick(item)}
              />
            ))}
          </div>
        </section>
      ))}
      {hasMore && (
        <div ref={sentinelRef} style={sentinelStyle}>
          {isLoadingMore && <Spinner size="s" />}
        </div>
      )}
    </>
  );
};
