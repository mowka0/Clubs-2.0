import { FC, useCallback, useEffect, useRef, useState } from 'react';
import { List, Spinner, Placeholder } from '@telegram-apps/telegram-ui';
import { useClubsStore } from '../store/useClubsStore';
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
  const { clubs, totalPages, loading, error, fetchClubs } = useClubsStore();
  const [filters, setFilters] = useState<ClubFilters>({});
  const [page, setPage] = useState(0);
  const debouncedFilters = useDebounce(filters, 300);
  const sentinelRef = useRef<HTMLDivElement>(null);

  // Reset page when filters change
  useEffect(() => {
    setPage(0);
  }, [debouncedFilters]);

  // Fetch clubs
  useEffect(() => {
    const params: ClubFilters = {
      ...debouncedFilters,
      page: String(page),
      size: '20',
    };
    fetchClubs(params);
  }, [debouncedFilters, page, fetchClubs]);

  // Infinite scroll observer
  const hasMore = page + 1 < totalPages;
  const handleObserver = useCallback(
    (entries: IntersectionObserverEntry[]) => {
      if (entries[0].isIntersecting && hasMore && !loading) {
        setPage((p) => p + 1);
      }
    },
    [hasMore, loading]
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
          {error}
        </div>
      )}

      {!loading && clubs.length === 0 && !error && (
        <Placeholder
          header="Клубы не найдены"
          description="Попробуйте изменить фильтры"
        />
      )}

      {clubs.map((club) => (
        <ClubCard key={club.id} club={club} />
      ))}

      {loading && (
        <div style={{ display: 'flex', justifyContent: 'center', padding: 24 }}>
          <Spinner size="m" />
        </div>
      )}

      <div ref={sentinelRef} style={{ height: 1 }} />
    </List>
  );
};
