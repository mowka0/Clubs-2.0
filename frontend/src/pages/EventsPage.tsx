import { FC, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import { useMyEventsQuery } from '../queries/events';
import { BrandBackdrop } from '../components/BrandBackdrop';
import { FeedSection } from '../components/feed/FeedSection';
import { FeedSkeleton } from '../components/feed/FeedSkeleton';
import { FeedEmpty } from '../components/feed/FeedEmpty';
import { EventCard } from '../components/feed/EventCard';
import { groupMyEvents } from '../utils/feedGrouping';

const CALENDAR_ICON = (
  <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <line x1="16" y1="2" x2="16" y2="6" />
    <line x1="8" y1="2" x2="8" y2="6" />
    <line x1="3" y1="10" x2="21" y2="10" />
  </svg>
);

export const EventsPage: FC = () => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const myEventsQuery = useMyEventsQuery();

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

  const handleRefresh = () => {
    haptic.impact('light');
    myEventsQuery.refetch();
  };

  const isLoadingInitial = myEventsQuery.isPending;
  const isError = myEventsQuery.isError && !myEventsQuery.isPending;
  const isEmpty = !isLoadingInitial && !isError && events.length === 0;

  return (
    <div className="brand-page">
      <BrandBackdrop />

      <header className="mc-hero">
        <div className="mc-hero-row">
          <h1>
            Твои <span className="accent">активности</span>
          </h1>
          <button
            type="button"
            className="mc-create-btn"
            onClick={handleRefresh}
            disabled={myEventsQuery.isFetching}
            aria-label="Обновить ленту"
          >
            Обновить
          </button>
        </div>
      </header>

      {isLoadingInitial && <FeedSkeleton count={3} />}

      {isError && (
        <FeedEmpty
          icon={CALENDAR_ICON}
          title="Не удалось загрузить события"
          description="Проверьте соединение и попробуйте снова."
          ctaLabel="Повторить"
          onCta={handleRefresh}
        />
      )}

      {isEmpty && (
        <FeedEmpty
          icon={CALENDAR_ICON}
          title="Пока нет событий"
          description="Найдите интересные клубы в Поиске — они появятся здесь, как только запланируют встречу."
          ctaLabel="Перейти в Поиск"
          onCta={handleSearchClick}
        />
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
    </div>
  );
};
