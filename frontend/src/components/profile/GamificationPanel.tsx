import { FC } from 'react';
import type { GamificationDto } from '../../types/api';

interface GamificationPanelProps {
  data: GamificationDto;
}

/**
 * Self-view gamification panel: level + XP progress bar + earned badges. XP is participation-only
 * and never decreases (see reputation-v2.md §H3). Presentational — the parent owns the query.
 */
export const GamificationPanel: FC<GamificationPanelProps> = ({ data }) => {
  const isMax = data.xpSpanToNext === null || data.nextLevelName === null;
  // Clamp to [0,1]; xpSpanToNext is the span of the current level, xpIntoLevel the progress into it.
  const ratio = isMax || !data.xpSpanToNext
    ? 1
    : Math.max(0, Math.min(1, data.xpIntoLevel / data.xpSpanToNext));

  return (
    <div className="rd-glass" style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 8 }}>
        <div style={{ fontSize: 18, fontWeight: 700 }}>{data.levelName}</div>
        <div style={{ fontSize: 13, color: 'var(--tgui--hint_color)' }}>
          {data.xp} XP · ур. {data.level}
        </div>
      </div>

      <div
        style={{
          marginTop: 10,
          height: 8,
          borderRadius: 999,
          background: 'var(--tgui--secondary_fill, rgba(0,0,0,0.08))',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${ratio * 100}%`,
            height: '100%',
            borderRadius: 999,
            background: 'linear-gradient(90deg, #ff8a3d, #ff5e8a)',
            transition: 'width 240ms ease',
          }}
        />
      </div>

      <div style={{ marginTop: 6, fontSize: 12, color: 'var(--tgui--hint_color)' }}>
        {isMax
          ? 'Максимальный уровень'
          : `${data.xpIntoLevel} / ${data.xpSpanToNext} XP до «${data.nextLevelName}»`}
      </div>

      {data.badges.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 12 }}>
          {data.badges.map((b) => (
            <span
              key={b.id}
              className="rd-tag"
              title={b.name}
              style={{ fontSize: 12 }}
            >
              {b.name}
            </span>
          ))}
        </div>
      )}
    </div>
  );
};
