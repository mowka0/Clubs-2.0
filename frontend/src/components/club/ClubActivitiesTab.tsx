import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Placeholder, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubActivitiesQuery } from '../../queries/activities';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';
import type { ActivityFilter, ActivityItemDto } from '../../api/activities';
import { ActivityFilterChips } from '../manage/ActivityFilterChips';
import { ActivityFeedList } from '../manage/ActivityFeedList';
import { FoxEmpty } from '../feed/FoxEmpty';
import foxCafeArt from '../../assets/mascot/fox-cafe.png';

interface ClubActivitiesTabProps {
  clubId: string;
  /** Владелец/со-организатор: пустое состояние получает CTA «Создать активность». */
  isManager: boolean;
}

export const ClubActivitiesTab: FC<ClubActivitiesTabProps> = ({ clubId, isManager }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const openCreateFlow = useCreateFlowStore((s) => s.open);

  const [filter, setFilter] = useState<ActivityFilter>('all');

  const activitiesQuery = useClubActivitiesQuery(clubId, {
    type: filter === 'all' ? undefined : filter,
  });

  const handleActivityClick = (activity: ActivityItemDto) => {
    haptic.impact('light');
    if (activity.type === 'event') navigate(`/events/${activity.id}`);
    else navigate(`/skladchina/${activity.id}`);
  };

  /**
   * CTA открывает единый флоу создания (живёт в AppDock): на странице своего клуба
   * он уже «заряжен» на текущий клуб — сразу выбор типа, без вопроса «какой клуб».
   */
  const handleCreateCta = () => {
    haptic.impact('light');
    openCreateFlow();
  };

  const feed = activitiesQuery.data;
  const isInitialLoading = activitiesQuery.isPending;
  const isError = activitiesQuery.isError && !isInitialLoading;
  // Лис показывается всегда, когда впереди пусто — даже если у клуба есть прошедшие
  // встречи: без запланированного «продолжения» таб для новичка выглядит так же мёртво.
  // История при этом не прячется: ActivityFeedList рендерит секцию «Прошедшие» под сценой.
  const showEmptyScene =
    !isInitialLoading && !isError && feed !== undefined && feed.upcoming.length === 0;
  const hasPast = (feed?.past.length ?? 0) > 0;

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

      {showEmptyScene && (
        <div style={{ padding: '0 20px' }}>
          {isManager ? (
            <FoxEmpty
              art={foxCafeArt}
              artLabel="Лис ждёт за столиком"
              cafeEffects
              soonIcon="📅"
              title={hasPast ? 'Ничего не запланировано' : 'Пока ни одной активности'}
              description={
                hasPast
                  ? 'Прошлые встречи на месте, а впереди пусто. Самое время позвать клуб на следующую.'
                  : 'Здесь появятся события и складчины клуба — участники увидят их сразу.'
              }
              primary={{ label: 'Создать активность', onClick: handleCreateCta }}
            />
          ) : (
            <FoxEmpty
              art={foxCafeArt}
              artLabel="Лис ждёт за столиком"
              cafeEffects
              soonIcon="📅"
              title={hasPast ? 'Новых активностей пока нет' : 'Активностей пока нет'}
              description={
                hasPast
                  ? 'Организатор ещё не запланировал следующую встречу. Как появится — увидишь здесь.'
                  : 'Организатор ещё не запланировал ни одного события. Как появится — увидишь здесь.'
              }
            />
          )}
        </div>
      )}

      {!isInitialLoading && !isError && feed !== undefined && (
        <ActivityFeedList feed={feed} onActivityClick={handleActivityClick} />
      )}
    </>
  );
};
