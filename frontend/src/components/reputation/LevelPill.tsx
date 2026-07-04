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
  /** Если задано, добавляет «· ур.N» (полная форма для hero-блока карточки заявки). */
  level?: number;
  /** `sm` — компактный чип строки инбокса; `md` (по умолчанию) — hero-пилюля карточки. */
  size?: 'sm' | 'md';
}

/** Пилюля глобального уровня геймификации — золото на верхнем тире, акцент на среднем, нейтраль на базовом. */
export const LevelPill: FC<LevelPillProps> = ({ levelName, tier, level, size = 'md' }) => (
  <span className={`rd-lvl-pill ${size === 'sm' ? 'rd-sm' : ''} ${TIER_CLASS[tier]}`}>
    {levelName}{level !== undefined ? ` · ур.${level}` : ''}
  </span>
);
