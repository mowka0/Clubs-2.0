import { FC } from 'react';
import { useHaptic } from '../../hooks/useHaptic';

interface ManageTabsProps<K extends string> {
  tabs: ReadonlyArray<{ key: K; label: string }>;
  active: K;
  onChange: (key: K) => void;
}

/**
 * Brand-styled, horizontally-scrollable pill-tabs for the Manage screen.
 *
 * Replaces telegram-ui `TabsList`, which truncated the 5 Russian labels
 * ("Учас…"). Pills never truncate — the row scrolls horizontally instead.
 * Active pill gets the brass fill. Haptic `selection` fires on change.
 */
export const ManageTabs = <K extends string>({
  tabs,
  active,
  onChange,
}: ManageTabsProps<K>): ReturnType<FC> => {
  const haptic = useHaptic();

  const handleClick = (key: K) => {
    if (key === active) return;
    haptic.select();
    onChange(key);
  };

  return (
    <div className="manage-tab-row" role="tablist" aria-label="Разделы управления">
      {tabs.map((tab) => {
        const selected = tab.key === active;
        return (
          <button
            key={tab.key}
            type="button"
            role="tab"
            aria-selected={selected}
            className={`manage-tab${selected ? ' active' : ''}`}
            onClick={() => handleClick(tab.key)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
};
