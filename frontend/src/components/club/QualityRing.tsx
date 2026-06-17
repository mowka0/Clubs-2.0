import { FC, ReactNode } from 'react';
import { MAX_LEVEL } from './qualityLevels';

const VIEWBOX = 64;
const RADIUS = 26;
const STROKE = 5;
const GAP = 8; // gap between sectors, in path units
const ROTATION = -126.2; // centres the 4 sectors at top/right/bottom/left, gaps on the diagonals
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
const SEGMENT = (CIRCUMFERENCE - MAX_LEVEL * GAP) / MAX_LEVEL; // one sector's arc length

/** dasharray that paints the first `level` of 4 equal sectors (rounded caps), then one long gap. */
function fillDashArray(level: number): string {
  const n = Math.max(0, Math.min(MAX_LEVEL, Math.round(level)));
  if (n === 0) return `0 ${CIRCUMFERENCE.toFixed(3)}`;
  const parts: number[] = [];
  for (let i = 0; i < n; i += 1) {
    parts.push(SEGMENT);
    if (i < n - 1) parts.push(GAP);
  }
  const drawn = n * SEGMENT + (n - 1) * GAP;
  parts.push(CIRCUMFERENCE - drawn);
  return parts.map((p) => p.toFixed(3)).join(' ');
}

interface QualityRingProps {
  /** Filled sectors, 0..4 (a generous discrete level — cosmetic, not a score). */
  level: number;
  /** Sector colour — a CSS var, e.g. `var(--live)` (Сплочённость) or `var(--accent)`. */
  color: string;
  /** Centre content: the distinct-absolute value + a tiny unit. */
  children: ReactNode;
  size?: number;
  ariaLabel?: string;
}

/**
 * A 4-equal-sector donut for the L2 club-quality rings. Track = 4 faint sectors; the first `level`
 * sectors are painted in `color`. Pure presentational — the caller decides level, color and center.
 * Style matches [DonutRing] from the reputation cards (same viewBox, radius and overlay).
 */
export const QualityRing: FC<QualityRingProps> = ({ level, color, children, size = 76, ariaLabel }) => {
  const center = VIEWBOX / 2;
  const trackDash = `${SEGMENT.toFixed(3)} ${GAP}`;
  return (
    <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }} role="img" aria-label={ariaLabel}>
      <svg width={size} height={size} viewBox={`0 0 ${VIEWBOX} ${VIEWBOX}`}>
        <circle
          cx={center}
          cy={center}
          r={RADIUS}
          fill="none"
          stroke="var(--hairline-2)"
          strokeWidth={STROKE}
          strokeLinecap="round"
          strokeDasharray={trackDash}
          transform={`rotate(${ROTATION} ${center} ${center})`}
        />
        {level > 0 && (
          <circle
            cx={center}
            cy={center}
            r={RADIUS}
            fill="none"
            stroke={color}
            strokeWidth={STROKE}
            strokeLinecap="round"
            strokeDasharray={fillDashArray(level)}
            transform={`rotate(${ROTATION} ${center} ${center})`}
          />
        )}
      </svg>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          lineHeight: 1,
        }}
      >
        {children}
      </div>
    </div>
  );
};
