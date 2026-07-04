import { FC, useCallback, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../hooks/useHaptic';

export interface CityChoice {
  readonly country: string;
  readonly city: string;
}

interface Country {
  readonly code: string;
  readonly name: string;
  readonly cities: readonly string[];
}

const COUNTRIES: readonly Country[] = [
  {
    code: 'RU',
    name: 'Россия',
    cities: [
      'Москва', 'Санкт-Петербург', 'Новосибирск', 'Екатеринбург',
      'Казань', 'Нижний Новгород', 'Краснодар', 'Ростов-на-Дону',
      'Самара', 'Уфа', 'Челябинск', 'Сочи', 'Воронеж',
      'Калининград', 'Владивосток',
    ],
  },
  {
    code: 'BY',
    name: 'Беларусь',
    cities: ['Минск', 'Гомель', 'Гродно', 'Брест', 'Витебск'],
  },
  {
    code: 'KZ',
    name: 'Казахстан',
    cities: ['Алматы', 'Астана', 'Шымкент', 'Караганда'],
  },
  {
    code: 'AM',
    name: 'Армения',
    cities: ['Ереван', 'Гюмри'],
  },
  {
    code: 'GE',
    name: 'Грузия',
    cities: ['Тбилиси', 'Батуми'],
  },
  {
    code: 'AE',
    name: 'ОАЭ',
    cities: ['Дубай', 'Абу-Даби'],
  },
  {
    code: 'TR',
    name: 'Турция',
    cities: ['Стамбул', 'Анталья'],
  },
] as const;

const STORAGE_KEY = 'clubs.cityChoice';
const DEFAULT_CHOICE: CityChoice = { country: 'RU', city: 'Москва' };

/** Читаемое название страны по сохранённому коду (например, 'RU' → 'Россия'). */
export function countryNameByCode(code: string | null | undefined): string | null {
  if (!code) return null;
  return COUNTRIES.find((c) => c.code === code)?.name ?? null;
}

function loadStored(): CityChoice {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_CHOICE;
    const parsed = JSON.parse(raw) as Partial<CityChoice>;
    if (typeof parsed.country !== 'string' || typeof parsed.city !== 'string') {
      return DEFAULT_CHOICE;
    }
    return { country: parsed.country, city: parsed.city };
  } catch {
    return DEFAULT_CHOICE;
  }
}

export function useCityChoice(): [CityChoice, (next: CityChoice) => void] {
  const [choice, setChoice] = useState<CityChoice>(loadStored);
  const update = useCallback((next: CityChoice) => {
    setChoice(next);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // localStorage недоступен в некоторых клиентах Telegram — выбор живёт только в памяти.
    }
  }, []);
  return [choice, update];
}

interface CityPickerProps {
  value: CityChoice;
  onChange: (next: CityChoice) => void;
  onClose: () => void;
}

const CHECK_ICON = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

export const CityPicker: FC<CityPickerProps> = ({ value, onChange, onClose }) => {
  const haptic = useHaptic();
  const [activeCountry, setActiveCountry] = useState<string>(value.country);

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

  const country = useMemo(
    () => COUNTRIES.find((c) => c.code === activeCountry) ?? COUNTRIES[0],
    [activeCountry],
  );

  const handlePick = (city: string) => {
    haptic.select();
    onChange({ country: country.code, city });
    onClose();
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div
        className="rd-sheet"
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

        <div className="rd-sheet-tabs" role="tablist" aria-label="Страна">
          {COUNTRIES.map((c) => {
            const isActive = c.code === activeCountry;
            return (
              <button
                key={c.code}
                type="button"
                role="tab"
                aria-selected={isActive}
                className={`rd-cat-chip${isActive ? ' rd-active' : ''}`}
                onClick={() => {
                  haptic.select();
                  setActiveCountry(c.code);
                }}
              >
                {c.name}
              </button>
            );
          })}
        </div>

        <div className="rd-sheet-body">
          {country.cities.map((city) => {
            const isSelected = value.country === country.code && value.city === city;
            return (
              <button
                key={city}
                type="button"
                className={`rd-pick-item${isSelected ? ' rd-selected' : ''}`}
                onClick={() => handlePick(city)}
              >
                <span>{city}</span>
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
