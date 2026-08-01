import { FC, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useCities } from '../queries/cities';
import { FoxEmpty } from './feed/FoxEmpty';
import foxFilterArt from '../assets/mascot/fox-filter.png';
import type { CityDto } from '../types/api';

/**
 * Названия стран живут на фронте, а не в справочнике: это локализация интерфейса, а не данные
 * о городах. Ключи — коды ISO из `cities.country_code`.
 */
const COUNTRY_NAMES: Record<string, string> = {
  RU: 'Россия',
  BY: 'Беларусь',
  KZ: 'Казахстан',
  AM: 'Армения',
  GE: 'Грузия',
  AE: 'ОАЭ',
  TR: 'Турция',
};

// Порядок вкладок: сначала страны с наибольшим числом городов у нас, дальше по списку.
const COUNTRY_ORDER = ['RU', 'BY', 'KZ', 'AM', 'GE', 'AE', 'TR'];

const STORAGE_KEY = 'clubs.cityId';

// Потолок выдачи поиска: список рисуется целиком, а сотни строк на один запрос никто не листает.
const SEARCH_RESULT_LIMIT = 50;

/** Ключ поиска: тот же, что в `cities.normalized_name` — lower + ё→е. Дефис сохраняем. */
function normalize(value: string): string {
  return value.trim().toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ');
}

export function countryNameByCode(code: string | null | undefined): string | null {
  if (!code) return null;
  return COUNTRY_NAMES[code] ?? null;
}

/**
 * Подпись под названием города. Регион показываем ТОЛЬКО тёзкам (`needsRegion`) — иначе область
 * лезла бы в 99% строк, где она не нужна. У городов федерального значения регион совпадает с
 * именем («Москва · Москва»), поэтому там остаётся одна страна.
 */
export function citySubtitle(city: CityDto): string | null {
  if (!city.needsRegion) return null;
  const country = countryNameByCode(city.countryCode);
  if (!city.region || city.region === city.name) return country;
  return country ? `${city.region}, ${country}` : city.region;
}

function loadStoredId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * Город каталога. Источники — от самого явного к общему:
 *
 * 1. **явный выбор** человека в пикере (переживает перезапуск, лежит в localStorage);
 * 2. **город из профиля** — человек уже указал, где живёт, спрашивать второй раз незачем;
 * 3. первый featured-город справочника — когда не известно ничего.
 *
 * Профиль не перебивает выбор: сменив город в каталоге, человек ожидает, что каталог останется
 * в нём, даже если в профиле стоит другой.
 */
export function useCityChoice(): [CityDto | null, (next: CityDto) => void] {
  const profileCityId = useAuthStore((s) => s.user?.cityId ?? null);
  const { data: cities } = useCities();
  const [pickedId, setPickedId] = useState<string | null>(loadStoredId);

  const update = useCallback((next: CityDto) => {
    setPickedId(next.id);
    try {
      localStorage.setItem(STORAGE_KEY, next.id);
    } catch {
      // localStorage недоступен в некоторых клиентах Telegram — выбор живёт только в памяти.
    }
  }, []);

  const choice = useMemo(() => {
    if (!cities?.length) return null;
    const byId = (id: string | null) => (id ? cities.find((c) => c.id === id) ?? null : null);
    return byId(pickedId) ?? byId(profileCityId) ?? cities.find((c) => c.isFeatured) ?? cities[0] ?? null;
  }, [cities, pickedId, profileCityId]);

  return [choice, update];
}

interface CityPickerProps {
  /** Выбранный город; null — ничего не выбрано (пустой профиль). */
  value: CityDto | null;
  onChange: (next: CityDto) => void;
  onClose: () => void;
}

const CHECK_ICON = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

export const CityPicker: FC<CityPickerProps> = ({ value, onChange, onClose }) => {
  const haptic = useHaptic();
  const { data: cities, isLoading } = useCities();
  const [activeCountry, setActiveCountry] = useState<string>(value?.countryCode ?? 'RU');
  const [query, setQuery] = useState('');

  // Блокируем скролл фона, пока открыт шит
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  // Закрываем по Escape
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  /**
   * Поле поиска не принимало ввод, когда пикер открыт из формы создания клуба
   * (баг PO 2026-08-01). Причина — та же природа, что у некликабельности: модалка формы
   * это Radix Dialog, а он запирает фокус внутри себя. Его `FocusScope` слушает на
   * `document` события `focusin` / `focusout` и, увидев фокус вне своего контента,
   * немедленно возвращает его обратно — курсор из нашего поля выбивало в тот же кадр.
   *
   * Гасим оба события в ФАЗЕ ПЕРЕХВАТА, пока они касаются пикера: перехват на `document`
   * идёт до всплытия, поэтому обработчик Radix их не увидит. Сам фокус при этом уже
   * установлен браузером — событие лишь уведомление, и его остановка ничего не ломает.
   */
  const sheetRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const touchesPicker = (node: EventTarget | null) =>
      node instanceof Node && sheetRef.current !== null && sheetRef.current.contains(node);

    const guard = (e: FocusEvent) => {
      if (touchesPicker(e.target) || touchesPicker(e.relatedTarget)) e.stopPropagation();
    };
    document.addEventListener('focusin', guard, true);
    document.addEventListener('focusout', guard, true);
    return () => {
      document.removeEventListener('focusin', guard, true);
      document.removeEventListener('focusout', guard, true);
    };
  }, []);

  const countries = useMemo(() => {
    const present = new Set((cities ?? []).map((c) => c.countryCode));
    return COUNTRY_ORDER.filter((code) => present.has(code));
  }, [cities]);

  const normalizedQuery = normalize(query);

  /**
   * Пустой запрос — короткая витрина выбранной страны (featured + города с клубами), иначе
   * человек упирается в простыню из сотен строк. Как только он начинает печатать, ищем по
   * ВСЕМУ справочнику без оглядки на страну: житель Твери должен найти себя с первых букв.
   */
  const visible = useMemo(() => {
    const all = cities ?? [];
    if (!normalizedQuery) {
      return all.filter(
        (c) => c.countryCode === activeCountry && (c.isFeatured || c.hasClubs),
      );
    }
    return all
      .filter((c) => normalize(c.name).includes(normalizedQuery))
      .sort((a, b) => {
        // Совпадение с начала названия важнее совпадения в середине: «твер» → Тверь, не Ахтверь.
        const aStarts = normalize(a.name).startsWith(normalizedQuery);
        const bStarts = normalize(b.name).startsWith(normalizedQuery);
        if (aStarts !== bStarts) return aStarts ? -1 : 1;
        return 0;
      })
      .slice(0, SEARCH_RESULT_LIMIT);
  }, [cities, normalizedQuery, activeCountry]);

  const handlePick = (city: CityDto) => {
    haptic.select();
    onChange(city);
    onClose();
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay rd-over-modal" onClick={onClose} aria-hidden="true" />
      <div
        ref={sheetRef}
        className="rd-sheet rd-over-modal-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Выбор города"
      >
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Город</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>
            Закрыть
          </button>
        </div>

        <div className="rd-city-search">
          <input
            className="rd-input"
            type="search"
            inputMode="search"
            placeholder="Найти город"
            aria-label="Найти город"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        {/* Вкладки стран — навигация по витрине; при активном поиске они ни на что не влияют. */}
        {!normalizedQuery && (
          <div className="rd-sheet-tabs" role="tablist" aria-label="Страна">
            {countries.map((code) => {
              const isActive = code === activeCountry;
              return (
                <button
                  key={code}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  className={`rd-cat-chip${isActive ? ' rd-active' : ''}`}
                  onClick={() => {
                    haptic.select();
                    setActiveCountry(code);
                  }}
                >
                  {COUNTRY_NAMES[code] ?? code}
                </button>
              );
            })}
          </div>
        )}

        <div className="rd-sheet-body">
          {isLoading && <p className="rd-cta-hint">Загружаем города…</p>}

          {!isLoading && visible.length === 0 && (
            <FoxEmpty
              art={foxFilterArt}
              title="Не нашли такой город"
              description={
                normalizedQuery
                  ? 'Проверьте написание или выберите ближайший крупный город.'
                  : 'В этой стране пока нет городов для выбора.'
              }
            />
          )}

          {visible.map((city) => {
            const isSelected = value?.id === city.id;
            const subtitle = citySubtitle(city);
            return (
              <button
                key={city.id}
                type="button"
                className={`rd-pick-item${isSelected ? ' rd-selected' : ''}`}
                onClick={() => handlePick(city)}
              >
                <span className="rd-pick-text">
                  <span>{city.name}</span>
                  {subtitle && <span className="rd-pick-sub">{subtitle}</span>}
                </span>
                {isSelected && <span className="rd-check">{CHECK_ICON}</span>}
              </button>
            );
          })}
        </div>
      </div>
    </>,
    document.body,
  );
};
