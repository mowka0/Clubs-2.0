import { FC, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClubsQuery } from '../queries/clubs';
import { useClubCardFacts } from '../queries/clubQuality';
import { useMyReputationQuery } from '../queries/members';
import { tierWord, clubsPrepositional } from '../utils/reputationTier';
import { useAuthStore } from '../store/useAuthStore';
import { ClubCard } from '../components/ClubCard';
import { CityPicker, useCityChoice } from '../components/CityPicker';
import {
  PriceFilter,
  pillLabelFromRange,
  presetIdFromRange,
  type PriceRange,
} from '../components/PriceFilter';
import { useHaptic } from '../hooks/useHaptic';
import { useHighlight } from '../hooks/useHighlight';
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

function getInitials(name: string): string {
  return name
    .replace(/[«»"']/g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w.charAt(0).toUpperCase())
    .join('');
}

function pluralizeClubsIn(n: number): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return 'клубе';
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return 'клубах';
  return 'клубах';
}

const SEARCH_ICON = (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </svg>
);

const CHEVRON_DOWN = (
  <svg className="chev" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="m6 9 6 6 6-6" />
  </svg>
);

export const DiscoveryPage: FC = () => {
  const [filters, setFilters] = useState<ClubFilters>({});
  const [cityChoice, setCityChoice] = useCityChoice();
  // Пришёл сюда из онбординга по кнопке «Найти клубы в своём городе» — подсвечиваем
  // селектор города: человек должен запомнить, где он живёт, и в следующий раз найти сам.
  const cityHighlighted = useHighlight('city');
  const [priceRange, setPriceRange] = useState<PriceRange>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [priceOpen, setPriceOpen] = useState(false);
  const debouncedFilters = useDebounce(filters, 300);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const haptic = useHaptic();

  const { user } = useAuthStore();
  const reputationQuery = useMyReputationQuery();
  const rep = reputationQuery.data;
  const activeCount = rep?.activeClubs.length ?? 0;
  const global = rep?.global;
  const globalScore = global?.score ?? null;
  // Score 0-100 + tier word + breadth ("опыт в N клубах"); the internal "N из M" feeds ranking.
  const hasReputation = activeCount > 0 || (global?.trackRecordClubs ?? 0) > 0;
  const reliablePhrase =
    global && global.trackRecordClubs > 0 && globalScore !== null
      ? `${tierWord(globalScore)} · опыт в ${global.trackRecordClubs} ${clubsPrepositional(global.trackRecordClubs)}`
      : 'пока недостаточно истории';

  const fullName = user ? `${user.firstName}${user.lastName ? ` ${user.lastName}` : ''}` : '';

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

  // Факты качества подтягиваются пачкой на каждую загруженную страницу (см. useClubCardFacts).
  const factsByClub = useClubCardFacts(data?.pages ?? []);

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
  const priceActive = presetIdFromRange(priceRange) !== 'any';

  return (
    <div className="rd-page">
      <header className="rd-header">
        <div className="rd-avt">
          {user?.avatarUrl ? <img src={user.avatarUrl} alt="" /> : (getInitials(fullName) || '👤')}
        </div>
        <div className="rd-info">
          <div className="rd-name">
            {user?.firstName ?? 'Гость'}
            <span className="rd-badge-star" aria-hidden="true" />
          </div>
          <div className="rd-sub">
            {activeCount > 0
              ? `Состоишь в ${activeCount} ${pluralizeClubsIn(activeCount)}`
              : reputationQuery.error
                // Не утверждаем «нет клубов», если запрос репутации упал — это ложный
                // онбординг (F5-20). Откатываемся к нейтральному приветствию; список клубов
                // ниже не затронут (у него своя обработка ошибок).
                ? 'Клубы по интересам рядом'
                : 'Найди свой первый клуб'}
          </div>
        </div>
        <button
          type="button"
          className={cityHighlighted ? 'rd-city-pill rd-highlight-pulse' : 'rd-city-pill'}
          onClick={() => { haptic.select(); setPickerOpen(true); }}
          aria-label="Выбрать город"
        >
          {cityChoice.city}
          {CHEVRON_DOWN}
        </button>
      </header>

      {hasReputation && (
        <div className="rd-stats">
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">Репутация</div>
            <div className="rd-stat-value">{globalScore ?? '—'}</div>
            <div className="rd-stat-foot">{reliablePhrase}</div>
          </div>
          <div className="rd-stat rd-glass">
            <div className="rd-stat-label">В клубах</div>
            <div className="rd-stat-value rd-plain">{activeCount}</div>
            <div className="rd-stat-foot">активных участий</div>
          </div>
        </div>
      )}

      <div className="rd-section-h">
        Найди свой <span className="rd-accent">клуб</span>
      </div>

      <label className="rd-search">
        {SEARCH_ICON}
        <input
          type="search"
          placeholder="Беговой клуб, книжный, дегустации"
          value={filters.search ?? ''}
          onChange={(e) => setFilters((f) => ({ ...f, search: e.target.value || undefined }))}
          aria-label="Поиск клубов"
        />
      </label>

      <div className="rd-cat-chips" role="tablist" aria-label="Категории">
        {CATEGORY_CHIPS.map((chip) => {
          const isActive = activeCategory === chip.value;
          return (
            <button
              key={chip.value || 'all'}
              type="button"
              role="tab"
              aria-selected={isActive}
              className={isActive ? 'rd-cat-chip rd-active' : 'rd-cat-chip'}
              onClick={() => handleCategoryClick(chip.value)}
            >
              {chip.label}
            </button>
          );
        })}
        <button
          type="button"
          className={priceActive ? 'rd-cat-chip rd-active' : 'rd-cat-chip'}
          onClick={() => { haptic.select(); setPriceOpen(true); }}
          aria-label="Фильтр по стоимости подписки"
        >
          {pillLabelFromRange(priceRange)}
        </button>
      </div>

      <div>
        {error && (
          <div className="rd-empty" role="alert">
            <div className="rd-sub">{error.message}</div>
          </div>
        )}

        {isPending && (
          <div className="rd-spinner-row"><Spinner size="m" /></div>
        )}

        {!isPending && clubs.length === 0 && !error && (
          <div className="rd-empty">
            <div className="rd-title">Клубы не найдены</div>
            <div className="rd-sub">Попробуйте изменить фильтры или город.</div>
          </div>
        )}

        {clubs.map((club) => (
          <ClubCard key={club.id} club={club} facts={factsByClub.get(club.id)} />
        ))}

        {isFetchingNextPage && (
          <div className="rd-spinner-row"><Spinner size="m" /></div>
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
