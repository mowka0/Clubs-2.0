import { FC, ReactNode } from 'react';

// Фиксированный размер viewBox SVG (масштабируется до реального размера через size).
const VIEWBOX = 64;
// Радиус дуги в координатах viewBox.
const RADIUS = 26;
// Толщина обводки по умолчанию, если strokeWidth не передан.
const DEFAULT_STROKE = 6;
// Длина окружности дуги — основа для strokeDasharray.
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

interface DonutRingProps {
  /** Отображаемый диаметр в px (SVG масштабируется от фиксированного viewBox 64). */
  size: number;
  /** Заполнение дуги как доля 0..1 (обрезается по границам). */
  fraction: number;
  /** Цвет обводки дуги — передавайте CSS-переменную, например `var(--accent)`. */
  color: string;
  /** Контент по центру: число, доля, подпись. */
  children: ReactNode;
  strokeWidth?: number;
  ariaLabel?: string;
}

/**
 * Презентационное SVG-кольцо-donut с произвольным контентом по центру. Используется совместно
 * карточкой участника клуба (Надёжность + Посещаемость) и карточкой заявки (надёжен в N из M клубов).
 * Чистый компонент: без загрузки данных, без доменного знания — вызывающий сам решает fraction,
 * цвет и подпись.
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
