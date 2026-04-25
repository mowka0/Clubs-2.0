import { FC } from 'react';
import { Input, Section } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';
import type { ClubFilters } from '../api/clubs';

const CATEGORIES = [
  { value: '', label: 'Все' },
  { value: 'sport', label: 'Спорт' },
  { value: 'creative', label: 'Творчество' },
  { value: 'food', label: 'Еда' },
  { value: 'board_games', label: 'Настолки' },
  { value: 'cinema', label: 'Кино' },
  { value: 'education', label: 'Образование' },
  { value: 'travel', label: 'Путешествия' },
  { value: 'other', label: 'Другое' },
];

interface ClubFiltersProps {
  filters: ClubFilters;
  onChange: (filters: ClubFilters) => void;
}

export const ClubFiltersComponent: FC<ClubFiltersProps> = ({ filters, onChange }) => {
  const haptic = useHaptic();

  const chipStyle = (active: boolean): React.CSSProperties => ({
    padding: '6px 12px',
    borderRadius: 20,
    fontSize: 13,
    cursor: 'pointer',
    border: 'none',
    background: active ? 'var(--tgui--button_color)' : 'var(--tgui--secondary_bg_color)',
    color: active ? 'var(--tgui--button_text_color)' : 'var(--tgui--text_color)',
    whiteSpace: 'nowrap' as const,
    flexShrink: 0,
  });

  return (
    <Section>
      <div style={{ padding: '8px 16px' }}>
        <Input
          placeholder="Поиск по названию"
          value={filters.search ?? ''}
          onChange={(e) => onChange({ ...filters, search: e.target.value || undefined })}
        />
      </div>
      <div style={{ padding: '4px 16px 8px', display: 'flex', gap: 8, overflowX: 'auto' }}>
        {CATEGORIES.map((cat) => (
          <button
            key={cat.value}
            style={chipStyle(filters.category === cat.value || (!filters.category && cat.value === ''))}
            onClick={() => {
              haptic.select();
              onChange({ ...filters, category: cat.value || undefined });
            }}
          >
            {cat.label}
          </button>
        ))}
      </div>
      <div style={{ padding: '0 16px 8px' }}>
        <Input
          placeholder="Город"
          value={filters.city ?? ''}
          onChange={(e) => onChange({ ...filters, city: e.target.value || undefined })}
        />
      </div>
    </Section>
  );
};
