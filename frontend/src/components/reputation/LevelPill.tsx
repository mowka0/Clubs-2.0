import { FC } from 'react';
import type { LevelTier } from '../../types/api';

const TIER_CLASS: Record<LevelTier, string> = {
  top: 'rd-lvl-pill--top',
  mid: 'rd-lvl-pill--mid',
  base: 'rd-lvl-pill--base',
};

interface LevelPillProps {
  levelName: string;
  tier: LevelTier;
  /** When set, appends "· ур.N" (full form for the application card hero). */
  level?: number;
  /** `sm` is the compact inbox-row chip; `md` (default) is the card hero pill. */
  size?: 'sm' | 'md';
}

/** Global gamification level pill — gold at the top tier, accent mid, neutral base. */
export const LevelPill: FC<LevelPillProps> = ({ levelName, tier, level, size = 'md' }) => (
  <span className={`rd-lvl-pill ${size === 'sm' ? 'rd-sm' : ''} ${TIER_CLASS[tier]}`}>
    {levelName}{level !== undefined ? ` · ур.${level}` : ''}
  </span>
);
