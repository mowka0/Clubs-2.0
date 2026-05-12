import { FC, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../hooks/useHaptic';

export interface PriceRange {
  readonly min?: number;
  readonly max?: number;
}

interface Preset {
  readonly id: string;
  readonly label: string;
  readonly shortLabel: string; // shown on the pill when this preset is active
  readonly range: PriceRange;  // empty object = "any"
}

const PRESETS: readonly Preset[] = [
  { id: 'any',     label: 'Любая',           shortLabel: 'Любая',           range: {} },
  { id: 'free',    label: 'Бесплатно',       shortLabel: 'Бесплатно',       range: { min: 0, max: 0 } },
  { id: 'lt1000',  label: 'До 1 000 ₽',      shortLabel: 'до 1 000 ₽',      range: { max: 1000 } },
  { id: '1to3k',   label: '1 000 – 3 000 ₽', shortLabel: '1 000–3 000 ₽',   range: { min: 1000, max: 3000 } },
  { id: 'gt3000',  label: 'От 3 000 ₽',      shortLabel: 'от 3 000 ₽',      range: { min: 3000 } },
] as const;

export const PRICE_PRESETS = PRESETS;

/** Match a min/max pair back to a preset id; "any" when nothing is set. */
export function presetIdFromRange(range: PriceRange): string {
  const found = PRESETS.find(
    (p) => p.range.min === range.min && p.range.max === range.max,
  );
  return found?.id ?? 'any';
}

export function pillLabelFromRange(range: PriceRange): string {
  const id = presetIdFromRange(range);
  if (id === 'any') return 'Цена';
  const preset = PRESETS.find((p) => p.id === id)!;
  return `Цена · ${preset.shortLabel}`;
}

interface PriceFilterProps {
  value: PriceRange;
  onChange: (next: PriceRange) => void;
  onClose: () => void;
}

const CHECK_ICON = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

export const PriceFilter: FC<PriceFilterProps> = ({ value, onChange, onClose }) => {
  const haptic = useHaptic();
  const activeId = presetIdFromRange(value);

  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const handlePick = (preset: Preset) => {
    haptic.select();
    onChange(preset.range);
    onClose();
  };

  return createPortal(
    <>
      <div className="city-picker-overlay" onClick={onClose} aria-hidden="true" />
      <div
        className="city-picker-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Стоимость подписки"
      >
        <div className="city-picker-grabber" aria-hidden="true" />
        <div className="city-picker-header">
          <h2>Стоимость подписки</h2>
          <button type="button" className="city-picker-close" onClick={onClose}>
            Закрыть
          </button>
        </div>

        <div className="city-picker-list">
          {PRESETS.map((preset) => {
            const isSelected = preset.id === activeId;
            return (
              <button
                key={preset.id}
                type="button"
                className={isSelected ? 'city-picker-item selected' : 'city-picker-item'}
                onClick={() => handlePick(preset)}
              >
                <span>{preset.label}</span>
                {isSelected && <span className="check">{CHECK_ICON}</span>}
              </button>
            );
          })}
        </div>
      </div>
    </>,
    document.body,
  );
};
