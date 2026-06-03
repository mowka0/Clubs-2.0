import { FC } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import type { ActivityFilter } from '../../api/activities';

interface ActivityFilterChipsProps {
  value: ActivityFilter;
  onChange: (next: ActivityFilter) => void;
}

interface Chip {
  key: ActivityFilter;
  label: string;
}

const CHIPS: Chip[] = [
  { key: 'all', label: 'Все' },
  { key: 'event', label: 'События' },
  { key: 'skladchina', label: 'Сборы' },
];

export const ActivityFilterChips: FC<ActivityFilterChipsProps> = ({ value, onChange }) => {
  const haptic = useHaptic();

  const handleClick = (next: ActivityFilter) => {
    if (next === value) return;
    haptic.select();
    onChange(next);
  };

  return (
    <div className="rd-cat-chips" role="tablist" aria-label="Фильтр активностей">
      {CHIPS.map((chip) => {
        const selected = value === chip.key;
        return (
          <button
            key={chip.key}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => handleClick(chip.key)}
            className={selected ? 'rd-cat-chip rd-active' : 'rd-cat-chip'}
          >
            {chip.label}
          </button>
        );
      })}
    </div>
  );
};
