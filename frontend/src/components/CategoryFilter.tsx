import { FC, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useHaptic } from '../hooks/useHaptic';

export interface CategoryOption {
  readonly value: string; // '' = все категории (фильтр не применяется)
  readonly label: string;
}

/** Лейблы фильтра Discovery (education → «Книги», food → «Кулинария» — исторические
    имена чипов каталога, НЕ общий словарь categoryLabels: тот описывает клуб, этот — поиск). */
export const CATEGORY_OPTIONS: readonly CategoryOption[] = [
  { value: '',            label: 'Все' },
  { value: 'sport',       label: 'Спорт' },
  { value: 'creative',    label: 'Творчество' },
  { value: 'education',   label: 'Книги' },
  { value: 'food',        label: 'Кулинария' },
  { value: 'cinema',      label: 'Кино' },
  { value: 'board_games', label: 'Настолки' },
  { value: 'travel',      label: 'Путешествия' },
] as const;

/** Текст пилюли фильтра: выбранная категория или «Все». */
export function categoryPillLabel(category: string | undefined): string {
  if (!category) return 'Все';
  return CATEGORY_OPTIONS.find((o) => o.value === category)?.label ?? category;
}

interface CategoryFilterProps {
  value: string; // '' = все
  onChange: (next: string) => void;
  onClose: () => void;
}

const CHECK_ICON = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

/** Шторка выбора категории — зеркало PriceFilter (единый паттерн фильтров Discovery). */
export const CategoryFilter: FC<CategoryFilterProps> = ({ value, onChange, onClose }) => {
  const haptic = useHaptic();

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

  const handlePick = (option: CategoryOption) => {
    haptic.select();
    onChange(option.value);
    onClose();
  };

  return createPortal(
    <>
      <div className="rd-sheet-overlay" onClick={onClose} aria-hidden="true" />
      <div className="rd-sheet" role="dialog" aria-modal="true" aria-label="Категория клуба">
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <div className="rd-sheet-head">
          <h2>Категория</h2>
          <button type="button" className="rd-sheet-close" onClick={onClose}>
            Закрыть
          </button>
        </div>

        <div className="rd-sheet-body">
          {CATEGORY_OPTIONS.map((option) => {
            const isSelected = option.value === value;
            return (
              <button
                key={option.value || 'all'}
                type="button"
                className={`rd-pick-item${isSelected ? ' rd-selected' : ''}`}
                onClick={() => handlePick(option)}
              >
                <span>{option.label}</span>
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
