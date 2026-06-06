import { FC, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { Placeholder, Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useClubActivitiesQuery } from '../../queries/activities';
import type { ActivityFilter, ActivityItemDto, ActivityType } from '../../api/activities';
import { ActivityFilterChips } from '../manage/ActivityFilterChips';
import { ActivityFeedList } from '../manage/ActivityFeedList';

interface ClubActivitiesTabProps {
  clubId: string;
  /** Organizer of [clubId] — gates the inline «+ Создать» activity shortcut. */
  isOrganizer?: boolean;
}

const CREATE_OPTIONS: ReadonlyArray<{ type: ActivityType; emoji: string; title: string; subtitle: string }> = [
  { type: 'event', emoji: '🗓', title: 'Событие', subtitle: 'Встреча с датой, временем, лимитом' },
  { type: 'skladchina', emoji: '💰', title: 'Сбор', subtitle: 'Сбор денег на бронь / инвентарь / подарок' },
];

export const ClubActivitiesTab: FC<ClubActivitiesTabProps> = ({ clubId, isOrganizer = false }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();

  const [filter, setFilter] = useState<ActivityFilter>('all');
  const [createOpen, setCreateOpen] = useState(false);

  const activitiesQuery = useClubActivitiesQuery(clubId, {
    type: filter === 'all' ? undefined : filter,
  });

  const handleActivityClick = (activity: ActivityItemDto) => {
    haptic.impact('light');
    if (activity.type === 'event') navigate(`/events/${activity.id}`);
    else navigate(`/skladchina/${activity.id}`);
  };

  // Create flow is shortcut from the club page: the club is already known, so
  // we skip the global club picker and go straight to the create form.
  const handlePickType = (type: ActivityType) => {
    haptic.impact('medium');
    setCreateOpen(false);
    navigate(type === 'event' ? `/clubs/${clubId}/events/new` : `/clubs/${clubId}/skladchina/new`);
  };

  const feed = activitiesQuery.data;
  const isInitialLoading = activitiesQuery.isPending;
  const isError = activitiesQuery.isError && !isInitialLoading;
  const isEmpty =
    !isInitialLoading &&
    !isError &&
    feed !== undefined &&
    feed.upcoming.length === 0 &&
    feed.past.length === 0;

  return (
    <>
      {isOrganizer && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }}>
          <button
            type="button"
            className="rd-create-pill"
            onClick={() => { haptic.impact('light'); setCreateOpen(true); }}
          >
            <span className="rd-plus" aria-hidden="true">+</span> Создать
          </button>
        </div>
      )}

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

      {!isInitialLoading && !isError && feed !== undefined && !isEmpty && (
        <ActivityFeedList feed={feed} onActivityClick={handleActivityClick} />
      )}

      {createOpen && createPortal(
        <>
          <div className="rd-sheet-overlay" onClick={() => setCreateOpen(false)} aria-hidden="true" />
          <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Создать активность">
            <div className="rd-sheet-grabber" aria-hidden="true" />
            <div className="rd-sheet-head">
              <h2>Создать активность</h2>
              <button type="button" className="rd-sheet-close" onClick={() => setCreateOpen(false)}>Закрыть</button>
            </div>
            <div className="rd-sheet-body">
              {CREATE_OPTIONS.map((opt) => (
                <button
                  key={opt.type}
                  type="button"
                  className="rd-create-opt"
                  onClick={() => handlePickType(opt.type)}
                >
                  <span className="rd-emoji" aria-hidden="true">{opt.emoji}</span>
                  <span>
                    <span className="rd-ttl" style={{ display: 'block' }}>{opt.title}</span>
                    <span className="rd-sub" style={{ display: 'block' }}>{opt.subtitle}</span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        </>,
        document.body,
      )}
    </>
  );
};
