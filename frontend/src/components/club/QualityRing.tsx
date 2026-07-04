import { FC, ReactNode } from 'react';
import { MAX_LEVEL } from './qualityLevels';

// Размер SVG-viewBox кольца, в условных единицах
const VIEWBOX = 64;
// Радиус окружности-кольца
const RADIUS = 26;
// Толщина обводки кольца
const STROKE = 5;
const GAP = 8; // зазор между секторами, в единицах path
const ROTATION = -126.2; // центрирует 4 сектора сверху/справа/снизу/слева, зазоры — по диагоналям
// Длина окружности кольца (2πR)
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
const SEGMENT = (CIRCUMFERENCE - MAX_LEVEL * GAP) / MAX_LEVEL; // длина дуги одного сектора

/** dasharray, закрашивающий первые `level` из 4 равных секторов (со скруглёнными концами), затем один длинный зазор. */
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
  /** Закрашенные сектора, 0..4 (щедрый дискретный уровень — косметика, не строгий счёт). */
  level: number;
  /** Цвет сектора — CSS-переменная, например `var(--live)` (Сплочённость) или `var(--accent)`. */
  color: string;
  /** Центральное содержимое: конкретное абсолютное значение + мелкая единица измерения. */
  children: ReactNode;
  size?: number;
  ariaLabel?: string;
}

/**
 * Пончик с 4 равными секторами для колец L2 клубовой квалификации. Трек = 4 тусклых сектора;
 * первые `level` секторов закрашены в `color`. Чисто презентационный — уровень, цвет и центр
 * решает вызывающий код. Стиль совпадает с [DonutRing] из карточек репутации (тот же viewBox,
 * радиус и наложение).
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
