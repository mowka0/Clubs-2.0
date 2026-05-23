import { FC, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Placeholder, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubActivitiesQuery } from '../../queries/activities';
import type { ActivityFilter, ActivityItemDto } from '../../api/activities';
import { ActivityFilterChips } from './ActivityFilterChips';
import { ActivityFeedList } from './ActivityFeedList';
import { CreateActivityPicker } from './CreateActivityPicker';

interface ActivitiesManageTabProps {
  clubId: string;
}

const stickyBarStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  zIndex: 5,
  padding: '12px 16px',
  background: 'var(--tgui--bg_color, transparent)',
  borderBottom: '1px solid var(--tgui--divider, rgba(255,255,255,0.08))',
};

export const ActivitiesManageTab: FC<ActivitiesManageTabProps> = ({ clubId }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const [filter, setFilter] = useState<ActivityFilter>('all');
  const [pickerOpen, setPickerOpen] = useState(false);

  const activitiesQuery = useClubActivitiesQuery(clubId, {
    type: filter === 'all' ? undefined : filter,
  });

  const activities = useMemo<ActivityItemDto[]>(
    () => activitiesQuery.data?.pages.flatMap((p) => p.content) ?? [],
    [activitiesQuery.data],
  );

  const handleOpenPicker = () => {
    haptic.impact('light');
    setPickerOpen(true);
  };

  const handleSelectEvent = () => {
    navigate(`/clubs/${clubId}/events/new`);
  };

  const handleSelectSkladchina = () => {
    navigate(`/clubs/${clubId}/skladchina/new`);
  };

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
      <div style={stickyBarStyle}>
        <Button mode="filled" size="m" stretched onClick={handleOpenPicker}>
          + Создать
        </Button>
      </div>

      <ActivityFilterChips value={filter} onChange={setFilter} />

      {isInitialLoading && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 32 }}>
          <Spinner size="m" />
        </div>
      )}

      {isError && (
        <Placeholder
          header="Ошибка"
          description={activitiesQuery.error?.message ?? 'Не удалось загрузить активности'}
        />
      )}

      {isEmpty && (
        <Placeholder description="В клубе пока нет активностей. Создайте первую через «+ Создать»." />
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

      <CreateActivityPicker
        open={pickerOpen}
        onClose={() => setPickerOpen(false)}
        onSelectEvent={handleSelectEvent}
        onSelectSkladchina={handleSelectSkladchina}
      />
    </>
  );
};
