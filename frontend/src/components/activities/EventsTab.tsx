import { FC, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import { useMyEventsQuery } from '../../queries/events';
import { useOrganizerClubs } from '../../queries/organizerClubs';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';
import { FeedSection } from '../feed/FeedSection';
import { FeedSkeleton } from '../feed/FeedSkeleton';
import { FoxEmpty } from '../feed/FoxEmpty';
import { EventCard } from '../feed/EventCard';
import { groupMyEvents } from '../../utils/feedGrouping';
import foxPlanningArt from '../../assets/mascot/fox-planning.png';
import foxCatalogArt from '../../assets/mascot/fox-catalog.png';
import foxErrorArt from '../../assets/mascot/fox-error.png';

export const EventsTab: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const myEventsQuery = useMyEventsQuery();
  // Роль-развилка пустого состояния: организатору бессмысленно предлагать Поиск —
  // его путь к первому событию лежит через единый флоу создания.
  const { clubs: organizerClubs, isLoading: isRoleLoading } = useOrganizerClubs();
  const isOrganizer = organizerClubs.length > 0;
  const openCreateFlow = useCreateFlowStore((s) => s.open);

  const events = useMemo(
    () => myEventsQuery.data?.pages.flatMap((p) => p.content) ?? [],
    [myEventsQuery.data],
  );
  const sections = useMemo(() => groupMyEvents(events), [events]);

  const loadMoreRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const target = loadMoreRef.current;
    if (!target || !myEventsQuery.hasNextPage || myEventsQuery.isFetchingNextPage) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          myEventsQuery.fetchNextPage();
        }
      },
      { rootMargin: '200px' },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [myEventsQuery.hasNextPage, myEventsQuery.isFetchingNextPage, myEventsQuery.fetchNextPage]);

  const handleEventClick = (id: string) => {
    haptic.impact('light');
    navigate(`/events/${id}`);
  };

  const handleSearchClick = () => {
    haptic.impact('light');
    navigate('/');
  };

  const handleCreateClick = () => {
    haptic.impact('light');
    openCreateFlow();
  };

  const isLoadingInitial = myEventsQuery.isPending;
  const isError = myEventsQuery.isError && !myEventsQuery.isPending;
  const isEmpty = !isLoadingInitial && !isError && events.length === 0;
  // Пока роль не определена, пустую сцену держим на скелетоне — иначе организатор
  // на мгновение увидит участнический вариант с CTA «Перейти в Поиск».
  const isEmptySceneResolving = isEmpty && isRoleLoading;

  return (
    <>
      {(isLoadingInitial || isEmptySceneResolving) && <FeedSkeleton count={3} />}

      {isError && (
        <FoxEmpty
          art={foxErrorArt}
          variant="error"
          title="Не удалось загрузить события"
          description="Проверь соединение и попробуй ещё раз."
          primary={{ label: 'Повторить', onClick: () => { haptic.impact('light'); myEventsQuery.refetch(); } }}
        />
      )}

      {isEmpty && !isEmptySceneResolving && (
        isOrganizer ? (
          <FoxEmpty
            art={foxPlanningArt}
            soonIcon="📅"
            title="Пора запланировать встречу"
            description="В твоём клубе ещё нет событий. Создай первое — участники увидят его здесь и смогут проголосовать за дату"
            primary={{ label: 'Создать событие', onClick: handleCreateClick }}
          />
        ) : (
          <FoxEmpty
            art={foxCatalogArt}
            title="Пока нет событий"
            description="Найди интересные клубы в Поиске — они появятся здесь, как только запланируют встречу."
            primary={{ label: 'Перейти в Поиск', onClick: handleSearchClick }}
          />
        )
      )}

      {!isLoadingInitial && !isError && sections.map((section) => (
        <FeedSection
          key={section.key}
          title={section.title}
          count={section.events.length}
          accent={section.key === 'action_required'}
        >
          {section.events.map((event) => (
            <EventCard
              key={event.id}
              event={event}
              onClick={() => handleEventClick(event.id)}
            />
          ))}
        </FeedSection>
      ))}

      {myEventsQuery.hasNextPage && (
        <div ref={loadMoreRef} style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
          {myEventsQuery.isFetchingNextPage && <Spinner size="s" />}
        </div>
      )}
    </>
  );
};
