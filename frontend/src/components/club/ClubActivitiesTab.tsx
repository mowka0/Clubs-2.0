import { FC, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Placeholder, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubActivitiesQuery } from '../../queries/activities';
import type { ActivityFilter, ActivityItemDto } from '../../api/activities';
import { ActivityFilterChips } from '../manage/ActivityFilterChips';
import { ActivityFeedList } from '../manage/ActivityFeedList';

interface ClubActivitiesTabProps {
  clubId: string;
}

export const ClubActivitiesTab: FC<ClubActivitiesTabProps> = ({ clubId }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const [filter, setFilter] = useState<ActivityFilter>('all');

  const activitiesQuery = useClubActivitiesQuery(clubId, {
    type: filter === 'all' ? undefined : filter,
  });

  const activities = useMemo<ActivityItemDto[]>(
    () => activitiesQuery.data?.pages.flatMap((p) => p.content) ?? [],
    [activitiesQuery.data],
  );

  const handleActivityClick = (activity: ActivityItemDto) => {
    haptic.impact('light');
    if (activity.type === 'event') navigate(`/events/${activity.id}`);
    else navigate(`/skladchina/${activity.id}`);
  };

  const isInitialLoading = activitiesQuery.isPending;
  const isError = activitiesQuery.isError && !isInitialLoading;
  const isEmpty = !isInitialLoading && !isError && activities.length === 0;

  return (
    <>
      <ActivityFilterChips value={filter} onChange={setFilter} />

      {isInitialLoading && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
          <Spinner size="m" />
        </div>
      )}

      {isError && (
        <div style={{ padding: '0 20px' }}>
          <Placeholder
            header="Ошибка"
            description={activitiesQuery.error?.message ?? 'Не удалось загрузить активности'}
          />
        </div>
      )}

      {isEmpty && (
        <div style={{ padding: '0 20px' }}>
          <Placeholder description="В клубе пока нет активностей." />
        </div>
      )}

      {!isInitialLoading && !isError && activities.length > 0 && (
        <ActivityFeedList
          activities={activities}
          onActivityClick={handleActivityClick}
          loadMore={() => activitiesQuery.fetchNextPage()}
          hasMore={activitiesQuery.hasNextPage}
          isLoadingMore={activitiesQuery.isFetchingNextPage}
        />
      )}
    </>
  );
};
