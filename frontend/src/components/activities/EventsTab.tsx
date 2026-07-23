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
import { HistoryCard } from '../feed/HistoryCard';
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
  // Сцена лиса — при отсутствии ПРЕДСТОЯЩИХ (зеркало SkladchinasTab.hasActive, W3-03a):
  // прошедшие посещённые события живут в секции «История» ПОД сценой и не должны её прятать —
  // иначе юзер с одним старым событием никогда не увидит ни лиса, ни CTA «Создать событие».
  const hasUpcoming = events.some((e) => !e.isHistory);
  const hasHistory = events.some((e) => e.isHistory);
  const isEmpty = !isLoadingInitial && !isError && !hasUpcoming;
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
            soonIcon={hasHistory ? undefined : '📅'}
            title={hasHistory ? 'Предстоящих событий нет' : 'Событий пока нет'}
            description="В твоём клубе ещё нет событий. Создай первое — участники увидят его здесь и смогут проголосовать за дату"
            primary={{ label: 'Создать событие', onClick: handleCreateClick }}
          />
        ) : (
          <FoxEmpty
            art={foxCatalogArt}
            title={hasHistory ? 'Предстоящих событий нет' : 'Событий пока нет'}
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
        >
          {section.events.map((event) => (
            // ИНВАРИАНТ: история рендерится ТОЛЬКО компактным HistoryCard, никогда EventCard
            // (решение PO 2026-07-21, вариант B мокапа). После перевода истории на HistoryCard
            // в EventCard больше нет ветки isHistory — попади история туда, показался бы бейдж
            // «Подтверждён» (обещание на будущее, на прошедшей встрече читается неверно).
            section.key === 'history' ? (
              <HistoryCard
                key={event.id}
                dateISO={event.eventDatetime}
                title={event.title}
                subtitle={event.locationText ? `${event.clubName} · ${event.locationText}` : event.clubName}
                onClick={() => handleEventClick(event.id)}
              />
            ) : (
              <EventCard
                key={event.id}
                event={event}
                onClick={() => handleEventClick(event.id)}
              />
            )
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
