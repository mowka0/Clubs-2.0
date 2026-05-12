import { FC, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClubsQuery } from '../queries/clubs';
import { ClubCard } from '../components/ClubCard';
import { DiscoveryBackdrop } from '../components/DiscoveryBackdrop';
import { CityPicker, useCityChoice } from '../components/CityPicker';
import {
  PriceFilter,
  pillLabelFromRange,
  presetIdFromRange,
  type PriceRange,
} from '../components/PriceFilter';
import { useHaptic } from '../hooks/useHaptic';
import type { ClubFilters } from '../api/clubs';

const CATEGORY_CHIPS = [
  { value: '',            label: 'Все' },
  { value: 'sport',       label: 'Спорт' },
  { value: 'creative',    label: 'Творчество' },
  { value: 'education',   label: 'Книги' },
  { value: 'food',        label: 'Кулинария' },
  { value: 'cinema',      label: 'Кино' },
  { value: 'board_games', label: 'Настолки' },
  { value: 'travel',      label: 'Путешествия' },
] as const;

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

const SEARCH_ICON = (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </svg>
);

const CHEVRON_DOWN = (
  <svg className="chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="m6 9 6 6 6-6" />
  </svg>
);

export const DiscoveryPage: FC = () => {
  const [filters, setFilters] = useState<ClubFilters>({});
  const [cityChoice, setCityChoice] = useCityChoice();
  const [priceRange, setPriceRange] = useState<PriceRange>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [priceOpen, setPriceOpen] = useState(false);
  const debouncedFilters = useDebounce(filters, 300);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const haptic = useHaptic();

  const queryFilters = useMemo<ClubFilters>(
    () => ({
      ...debouncedFilters,
      city: cityChoice.city,
      minPrice: priceRange.min !== undefined ? String(priceRange.min) : undefined,
      maxPrice: priceRange.max !== undefined ? String(priceRange.max) : undefined,
      size: '20',
    }),
    [debouncedFilters, cityChoice.city, priceRange.min, priceRange.max],
  );

  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    fetchNextPage,
    hasNextPage,
  } = useClubsQuery(queryFilters);

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

  const handleCategoryClick = useCallback(
    (value: string) => {
      haptic.select();
      setFilters((prev) => ({ ...prev, category: value || undefined }));
    },
    [haptic],
  );

  const activeCategory = filters.category ?? '';

  return (
    <div className="discovery-page">
      <DiscoveryBackdrop />

      <header className="discovery-topbar">
        <div className="discovery-brand">
          <img src="/brand/logo.png" alt="Clubs" />
          <div>
            <div className="name">Clubs</div>
            <div className="tag">Сообщества</div>
          </div>
        </div>
        <button
          type="button"
          className="discovery-city-pill"
          onClick={() => {
            haptic.select();
            setPickerOpen(true);
          }}
          aria-label="Выбрать город"
        >
          {cityChoice.city}
          {CHEVRON_DOWN}
        </button>
      </header>

      <section className="discovery-hero">
        <h1>
          Найди свой{' '}
          <span className="accent">клуб</span>
        </h1>
      </section>

      <div className="discovery-search-wrap">
        <label className="discovery-search">
          {SEARCH_ICON}
          <input
            type="search"
            placeholder="Беговой клуб, книжный, дегустации"
            value={filters.search ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value || undefined }))}
            aria-label="Поиск клубов"
          />
        </label>
      </div>

      <div className="discovery-chips" role="tablist" aria-label="Категории">
        {CATEGORY_CHIPS.map((chip) => {
          const isActive = activeCategory === chip.value;
          return (
            <button
              key={chip.value || 'all'}
              type="button"
              role="tab"
              aria-selected={isActive}
              className={isActive ? 'discovery-chip active' : 'discovery-chip'}
              onClick={() => handleCategoryClick(chip.value)}
            >
              {chip.label}
            </button>
          );
        })}
      </div>

      <div className="discovery-meta-row">
        <span className="count">{clubs.length} {pluralizeClubs(clubs.length)}</span>
        <button
          type="button"
          className={
            presetIdFromRange(priceRange) === 'any'
              ? 'discovery-filter-pill'
              : 'discovery-filter-pill active'
          }
          onClick={() => {
            haptic.select();
            setPriceOpen(true);
          }}
          aria-label="Фильтр по стоимости подписки"
        >
          {pillLabelFromRange(priceRange)}
          {CHEVRON_DOWN}
        </button>
        <span className="sort">По релевантности</span>
      </div>

      <div className="discovery-list">
        {error && (
          <div className="discovery-error" role="alert">{error.message}</div>
        )}

        {isPending && (
          <div className="discovery-spinner-row"><Spinner size="m" /></div>
        )}

        {!isPending && clubs.length === 0 && !error && (
          <div className="discovery-empty">
            Клубы не найдены. Попробуйте изменить фильтры.
          </div>
        )}

        {clubs.map((club) => (
          <ClubCard key={club.id} club={club} />
        ))}

        {isFetchingNextPage && (
          <div className="discovery-spinner-row"><Spinner size="m" /></div>
        )}

        <div ref={sentinelRef} style={{ height: 1 }} />
      </div>

      {pickerOpen && (
        <CityPicker
          value={cityChoice}
          onChange={setCityChoice}
          onClose={() => setPickerOpen(false)}
        />
      )}

      {priceOpen && (
        <PriceFilter
          value={priceRange}
          onChange={setPriceRange}
          onClose={() => setPriceOpen(false)}
        />
      )}
    </div>
  );
};

function pluralizeClubs(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'клуб';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return 'клуба';
  return 'клубов';
}
