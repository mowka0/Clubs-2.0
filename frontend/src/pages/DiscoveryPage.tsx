import { FC, useCallback, useEffect, useRef, useState } from 'react';
import { List, Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubsQuery } from '../queries/clubs';
import { ClubCard } from '../components/ClubCard';
import { ClubFiltersComponent } from '../components/ClubFilters';
import type { ClubFilters } from '../api/clubs';

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

export const DiscoveryPage: FC = () => {
  const [filters, setFilters] = useState<ClubFilters>({});
  const debouncedFilters = useDebounce(filters, 300);
  const sentinelRef = useRef<HTMLDivElement>(null);

  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    fetchNextPage,
    hasNextPage,
  } = useClubsQuery({ ...debouncedFilters, size: '20' });

  const clubs = data?.pages.flatMap((p) => p.content) ?? [];

  const handleObserver = useCallback(
    (entries: IntersectionObserverEntry[]) => {
      if (entries[0].isIntersecting && hasNextPage && !isFetchingNextPage) {
        fetchNextPage();
      }
    },
    [hasNextPage, isFetchingNextPage, fetchNextPage],
  );

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(handleObserver, { threshold: 0.1 });
    observer.observe(el);
    return () => observer.disconnect();
  }, [handleObserver]);

  return (
    <List>
      <ClubFiltersComponent filters={filters} onChange={setFilters} />

      {error && (
        <div style={{ padding: 16, color: 'var(--tgui--destructive_text_color)', textAlign: 'center' }}>
          {error.message}
        </div>
      )}

      {!isPending && clubs.length === 0 && !error && (
        <Placeholder
          header="Клубы не найдены"
          description="Попробуйте изменить фильтры"
        />
      )}

      {clubs.map((club) => (
        <ClubCard key={club.id} club={club} />
      ))}

      {/* Initial load only — background refetches (staleTime expiry) must NOT show a spinner. */}
      {isPending && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
          <Spinner size="m" />
        </div>
      )}

      {/* Pagination spinner shown only while fetching the next page. */}
      {isFetchingNextPage && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
          <Spinner size="m" />
        </div>
      )}

      <div ref={sentinelRef} style={{ height: 1 }} />
    </List>
  );
};
