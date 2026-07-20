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
import foxFilterArt from '../../assets/mascot/fox-filter.png';

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

  // Сброс фильтра типа: возвращает в клуб честную ролевую сцену (или реальный список).
  const handleShowAll = () => {
    haptic.impact('light');
    setFilter('all');
  };

  const feed = activitiesQuery.data;
  const isInitialLoading = activitiesQuery.isPending;
  const isError = activitiesQuery.isError && !isInitialLoading;
  const isUpcomingEmpty =
    !isInitialLoading && !isError && feed !== undefined && feed.upcoming.length === 0;
  // Пустой upcoming под активным фильтром типа ≠ «клуб пуст»: ролевой лис-кафе тут врал бы
  // («ни одной активности» + «Создать активность»). Показываем честный текст про фильтр,
  // роль-развилка не нужна — создание в контексте фильтра не предлагаем.
  const showFilteredEmpty = isUpcomingEmpty && filter !== 'all';
  // Лис-кафе (ролевая сцена) — только при фильтре «Все». Если клуб реально пуст, после
  // «Показать все» честно появится именно она.
  const showEmptyScene = isUpcomingEmpty && filter === 'all';
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

      {showFilteredEmpty && (
        <div style={{ padding: '0 20px' }}>
          <FoxEmpty
            art={foxFilterArt}
            title="Нет активностей этого типа"
            description="Под выбранный фильтр ничего не попало. Сними его — увидишь всё, что есть в клубе."
            primary={{ label: 'Показать все', onClick: handleShowAll }}
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
