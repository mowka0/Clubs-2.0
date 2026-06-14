import { FC, ReactNode } from 'react';

const VIEWBOX = 64;
const RADIUS = 26;
const DEFAULT_STROKE = 6;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

interface DonutRingProps {
  /** Rendered diameter in px (the SVG scales from a fixed 64 viewBox). */
  size: number;
  /** Arc fill as a fraction 0..1 (clamped). */
  fraction: number;
  /** Arc stroke color — pass a CSS variable, e.g. `var(--accent)`. */
  color: string;
  /** Center content: number, fraction, sub-label. */
  children: ReactNode;
  strokeWidth?: number;
  ariaLabel?: string;
}

/**
 * A presentational SVG donut ring with arbitrary centered content. Shared by the per-club
 * member card (Надёжность + Посещаемость) and the application card (надёжен в N из M клубов).
 * Pure: no data fetching, no domain knowledge — the caller decides fraction, color and label.
 */
export const DonutRing: FC<DonutRingProps> = ({
  size,
  fraction,
  color,
  children,
  strokeWidth = DEFAULT_STROKE,
  ariaLabel,
}) => {
  const clamped = Math.max(0, Math.min(1, fraction));
  const dashArray = `${clamped * CIRCUMFERENCE} ${CIRCUMFERENCE}`;
  const center = VIEWBOX / 2;

  return (
    <div
      style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}
      role="img"
      aria-label={ariaLabel}
    >
      <svg width={size} height={size} viewBox={`0 0 ${VIEWBOX} ${VIEWBOX}`}>
        <circle cx={center} cy={center} r={RADIUS} fill="none" stroke="var(--hairline-2)" strokeWidth={strokeWidth} />
        <circle
          cx={center}
          cy={center}
          r={RADIUS}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={dashArray}
          transform={`rotate(-90 ${center} ${center})`}
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          lineHeight: 1.1,
        }}
      >
        {children}
      </div>
    </div>
  );
};
