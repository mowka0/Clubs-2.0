import { FC, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { useClubsQuery } from '../queries/clubs';
import { useClubCardFacts } from '../queries/clubQuality';
import { useMyReputationQuery } from '../queries/members';
import { useClubTopicSuggestQuery } from '../queries/interests';
import { useAuthStore } from '../store/useAuthStore';
import { ClubCard } from '../components/ClubCard';
import { WeekShelf } from '../components/WeekShelf';
import { CategoryFilter, categoryPillLabel } from '../components/CategoryFilter';
import { CityPicker, useCityChoice } from '../components/CityPicker';
import {
  PriceFilter,
  pillLabelFromRange,
  presetIdFromRange,
  type PriceRange,
} from '../components/PriceFilter';
import { useHaptic } from '../hooks/useHaptic';
import { FoxEmpty } from '../components/feed/FoxEmpty';
import foxErrorArt from '../assets/mascot/fox-error.png';
import foxCatalogArt from '../assets/mascot/fox-catalog.png';
import foxFilterArt from '../assets/mascot/fox-filter.png';
import type { ClubFilters } from '../api/clubs';
import { ScreenPreview } from '../components/onboarding/ScreenPreview';

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
  const [priceRange, setPriceRange] = useState<PriceRange>({});
  const [pickerOpen, setPickerOpen] = useState(false);
  const [categoryOpen, setCategoryOpen] = useState(false);
  const [priceOpen, setPriceOpen] = useState(false);
  // Выпадашка подсказок тем: открывается вводом, закрывается выбором, Escape и тапом мимо.
  // Отдельный флаг, а не производное «есть ли подсказки» — иначе после выбора темы она бы
  // всплывала снова: подсказка совпадает с тем, что теперь в поле.
  const [topicsOpen, setTopicsOpen] = useState(false);
  const debouncedFilters = useDebounce(filters, 300);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const haptic = useHaptic();
  const navigate = useNavigate();

  const { user } = useAuthStore();
  // Репутация здесь нужна только для подписи шапки «Состоишь в N клубах»
  // (плитки «Репутация / В клубах» снесены — репутация живёт в Профиле).
  const reputationQuery = useMyReputationQuery();
  const activeCount = reputationQuery.data?.activeClubs.length ?? 0;

  const fullName = user ? `${user.firstName}${user.lastName ? ` ${user.lastName}` : ''}` : '';

  // Подсказки берём от debounced-значения — того же, по которому строится и сам запрос выдачи,
  // так что подсказки и результаты не расходятся во времени.
  const topicSuggestQuery = useClubTopicSuggestQuery(debouncedFilters.search ?? '', topicsOpen);
  // Точное совпадение с уже введённым отбрасываем: подсказывать то, что человек и так набрал,
  // нечего — строка бы просто мигала под полем.
  const topicHints = (topicSuggestQuery.data ?? []).filter((t) => t !== filters.search?.trim().toLowerCase());

  // Тап мимо выпадашки закрывает её. Слушаем pointerdown, а не click: на тапе по самой
  // подсказке click ещё не случился, и закрытие по нему съедало бы выбор.
  useEffect(() => {
    if (!topicsOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as HTMLElement | null;
      if (!target?.closest('.rd-search-wrap')) setTopicsOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [topicsOpen]);

  const queryFilters = useMemo<ClubFilters>(
    () => ({
      ...debouncedFilters,
      // Фильтруем по id справочника: сравнение строк не находило клуб «мск» по запросу «Москва».
      cityId: cityChoice?.id,
      minPrice: priceRange.min !== undefined ? String(priceRange.min) : undefined,
      maxPrice: priceRange.max !== undefined ? String(priceRange.max) : undefined,
      size: '20',
    }),
    [debouncedFilters, cityChoice?.id, priceRange.min, priceRange.max],
  );

  const {
    data,
    error,
    isPending,
    isFetchingNextPage,
    fetchNextPage,
    hasNextPage,
    refetch,
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

  const priceActive = presetIdFromRange(priceRange) !== 'any';
  // Развилка пустых состояний: с активными фильтрами виноваты фильтры, без них — город.
  // Считаем от debouncedFilters (по ним и построен запрос), иначе сцена перескакивает
  // между «фильтры»/«город» на 300 мс дебаунса; цена дебаунса не имеет — берём текущую.
  const hasActiveFilters =
    Boolean(debouncedFilters.search) || Boolean(debouncedFilters.category) || priceActive;

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
          className="rd-city-pill"
          onClick={() => { haptic.select(); setPickerOpen(true); }}
          aria-label="Выбрать город"
        >
          {cityChoice?.name ?? 'Город'}
          {CHEVRON_DOWN}
        </button>
      </header>

      <div className="rd-section-h">
        Найди свой <span className="rd-accent">клуб</span>
      </div>

      {/* Одна строка «поиск + фильтры» (выбор PO: вариант 4 мокапа 15-search-merge):
          пилюли берут ширину по содержимому («Спорт», «до 1 000 ₽»), поиск —
          флексом занимает остаток и ужимается под них. */}
      <div className="rd-filter-row">
        {/* Обёртка нужна выпадашке подсказок: она позиционируется от поля, а не от строки
            фильтров, иначе уезжала бы под пилюли «Категория»/«Цена». */}
        <div className="rd-search-wrap">
          <label className="rd-search">
            {SEARCH_ICON}
            <input
              type="search"
              placeholder="Поиск клубов"
              value={filters.search ?? ''}
              onChange={(e) => {
                setFilters((f) => ({ ...f, search: e.target.value || undefined }));
                setTopicsOpen(true);
              }}
              onKeyDown={(e) => { if (e.key === 'Escape') setTopicsOpen(false); }}
              aria-label="Поиск клубов"
            />
          </label>
          {/* Подсказки тем: человеку нечем угадать, каким словом размечен клуб («настолки»,
              а не «настольные игры»). Приходят только темы, по которым в каталоге кто-то
              найдётся, — тап по пустой подсказке был бы хуже её отсутствия. */}
          {topicsOpen && topicHints.length > 0 && (
            <div className="rd-search-hints" role="listbox" aria-label="Подсказки тем">
              {topicHints.map((topic) => (
                <button
                  key={topic}
                  type="button"
                  className="rd-search-hint"
                  onClick={() => {
                    haptic.select();
                    setFilters((f) => ({ ...f, search: topic }));
                    setTopicsOpen(false);
                  }}
                >
                  {SEARCH_ICON}
                  <span>{topic}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <button
          type="button"
          className={filters.category ? 'rd-filter-pill rd-active' : 'rd-filter-pill'}
          onClick={() => { haptic.select(); setCategoryOpen(true); }}
          aria-label="Фильтр по категории"
        >
          {categoryPillLabel(filters.category)}
          {CHEVRON_DOWN}
        </button>
        <button
          type="button"
          className={priceActive ? 'rd-filter-pill rd-active' : 'rd-filter-pill'}
          onClick={() => { haptic.select(); setPriceOpen(true); }}
          aria-label="Фильтр по стоимости подписки"
        >
          {pillLabelFromRange(priceRange)}
          {CHEVRON_DOWN}
        </button>
      </div>

      <WeekShelf clubs={clubs} />

      <div>
        {error && clubs.length === 0 && (
          // Сырой error.message юзеру не показываем — там технические детали HTTP-слоя.
          <FoxEmpty
            art={foxErrorArt}
            variant="error"
            title="Не удалось загрузить клубы"
            description="Проверь соединение и попробуй ещё раз."
            primary={{ label: 'Повторить', onClick: () => { haptic.impact('light'); refetch(); } }}
          />
        )}

        {error && clubs.length > 0 && (
          // Упал рефетч при уже показанном списке: полноэкранная ошибка стёрла бы
          // рабочие данные — вместо этого компактная плашка, список ниже остаётся.
          <div className="rd-glass rd-empty" role="alert">
            <div className="rd-title">Не удалось обновить список</div>
            <button
              type="button"
              className="rd-ghost-btn"
              onClick={() => { haptic.impact('light'); refetch(); }}
            >
              Повторить
            </button>
          </div>
        )}

        {isPending && (
          <div className="rd-spinner-row"><Spinner size="m" /></div>
        )}

        {!isPending && clubs.length === 0 && !error && (
          hasActiveFilters ? (
            <FoxEmpty
              art={foxFilterArt}
              title="Ничего не нашлось"
              // Город здесь не интерполируем: он в именительном падеже, а фраза требует
              // родительного («все клубы Твери») — «твоего города» обходит склонение.
              description="Под выбранные фильтры клубов нет. Сбрось их — покажем все клубы твоего города."
              primary={{
                label: 'Сбросить фильтры',
                // Город намеренно не трогаем: сбрасываются только поиск/категория/цена.
                onClick: () => { haptic.impact('light'); setFilters({}); setPriceRange({}); },
              }}
            />
          ) : (
            <FoxEmpty
              art={foxCatalogArt}
              // «В твоём городе» вместо «В {city}»: город хранится в именительном падеже,
              // а сам город и так виден в пилюле шапки.
              title="В твоём городе пока нет клубов"
              description="Клубы появляются, когда организаторы создают их здесь. Загляни позже — или стань первым и начни зарабатывать на ведении своего платного клуба (можешь создать бесплатный, если хочешь)."
              primary={{
                label: 'Создать свой клуб',
                onClick: () => { haptic.impact('light'); navigate('/my-clubs', { state: { highlight: 'create-club' } }); },
              }}
              secondary={{ label: 'Сменить город', onClick: () => { haptic.impact('light'); setPickerOpen(true); } }}
            />
          )
        )}

        {clubs.map((club) => (
          <div key={club.id}>
            <ClubCard club={club} facts={factsByClub.get(club.id)} />
          </div>
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

      {categoryOpen && (
        <CategoryFilter
          value={filters.category ?? ''}
          onChange={(next) => setFilters((prev) => ({ ...prev, category: next || undefined }))}
          onClose={() => setCategoryOpen(false)}
        />
      )}

      {priceOpen && (
        <PriceFilter
          value={priceRange}
          onChange={setPriceRange}
          onClose={() => setPriceOpen(false)}
        />
      )}

      {/* Ждём конца загрузки, но не самих клубов: правила игры каталога нужны и тому,
          у кого в городе пока пусто. */}
      <ScreenPreview screen="DISCOVERY" ready={!isPending} />
    </div>
  );
};
