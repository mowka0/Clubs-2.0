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
    <div
      role="tablist"
      aria-label="Фильтр активностей"
      style={{
        display: 'flex',
        gap: 8,
        padding: '8px 16px',
        overflowX: 'auto',
      }}
    >
      {CHIPS.map((chip) => {
        const selected = value === chip.key;
        return (
          <button
            key={chip.key}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => handleClick(chip.key)}
            style={{
              flex: '0 0 auto',
              padding: '6px 14px',
              borderRadius: 999,
              border: selected
                ? '1px solid var(--brand-brass, #C9A063)'
                : '1px solid var(--tgui--divider, rgba(255,255,255,0.18))',
              background: selected
                ? 'var(--brand-brass, #C9A063)'
                : 'transparent',
              color: selected
                ? 'var(--brand-ink-1, #1A2138)'
                : 'var(--tgui--text_color, #fff)',
              fontSize: 14,
              fontWeight: selected ? 600 : 500,
              cursor: 'pointer',
              whiteSpace: 'nowrap',
            }}
          >
            {chip.label}
          </button>
        );
      })}
    </div>
  );
};
