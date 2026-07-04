import { FC, useEffect, useRef, useState } from 'react';
import { useHaptic } from '../hooks/useHaptic';

interface Props {
  /** Изображение для полноэкранного показа; null/пустая строка держат лайтбокс закрытым. */
  src: string | null;
  alt?: string;
  onClose: () => void;
}

// Минимальный масштаб (1 = исходный размер, меньше не уменьшаем).
const MIN_SCALE = 1;
// Максимальный масштаб pinch-зума.
const MAX_SCALE = 5;
// Масштаб, устанавливаемый двойным тапом.
const DOUBLE_TAP_SCALE = 2.5;
// Максимальный интервал между тапами, чтобы засчитать двойной тап (мс).
const DOUBLE_TAP_MS = 300;

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

function touchDistance(t: React.TouchList): number {
  const dx = t[0].clientX - t[1].clientX;
  const dy = t[0].clientY - t[1].clientY;
  return Math.hypot(dx, dy);
}

/**
 * Полноэкранный просмотрщик изображений с pinch-зумом (например, чека — чтобы каждый прочитал его и
 * посчитал свою долю). Нативный pinch выключен на всё приложение viewport-метой (`user-scalable=no`),
 * поэтому зум реализован здесь на CSS-transform: pinch двумя пальцами масштабирует, drag одним пальцем
 * панорамирует в зуме, двойной тап переключает зум. `touch-action: none` на изображении отдаёт все
 * жесты нам. Тап по фону / ✕ / Escape закрывает; тап по изображению — нет. Переиспользуемый: любая
 * `<img>`-миниатюра открывает его, поднимая `src` в state.
 */
export const ImageLightbox: FC<Props> = ({ src, alt = '', onClose }) => {
  const haptic = useHaptic();
  const [scale, setScale] = useState(1);
  const [tx, setTx] = useState(0);
  const [ty, setTy] = useState(0);

  const pinch = useRef<{ startDist: number; startScale: number } | null>(null);
  const pan = useRef<{ startX: number; startY: number; startTx: number; startTy: number } | null>(null);
  const lastTap = useRef(0);

  const resetZoom = () => { setScale(1); setTx(0); setTy(0); };

  // Синхронизация с внешним миром: закрытие по Escape, блокировка скролла body и сброс зума при смене
  // изображения или повторном открытии. Cleanup возвращает скролл — эффект перезапускается по `src`.
  useEffect(() => {
    if (!src) return;
    resetZoom();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [src, onClose]);

  if (!src) return null;

  const close = () => {
    haptic.impact('light');
    onClose();
  };

  const onTouchStart = (e: React.TouchEvent) => {
    if (e.touches.length === 2) {
      pinch.current = { startDist: touchDistance(e.touches), startScale: scale };
      pan.current = null;
    } else if (e.touches.length === 1 && scale > 1) {
      pan.current = { startX: e.touches[0].clientX, startY: e.touches[0].clientY, startTx: tx, startTy: ty };
    }
  };

  const onTouchMove = (e: React.TouchEvent) => {
    if (pinch.current && e.touches.length === 2) {
      const next = clamp(pinch.current.startScale * (touchDistance(e.touches) / pinch.current.startDist), MIN_SCALE, MAX_SCALE);
      setScale(next);
    } else if (pan.current && e.touches.length === 1) {
      setTx(pan.current.startTx + (e.touches[0].clientX - pan.current.startX));
      setTy(pan.current.startTy + (e.touches[0].clientY - pan.current.startY));
    }
  };

  const onTouchEnd = (e: React.TouchEvent) => {
    pinch.current = null;
    pan.current = null;
    if (e.touches.length > 0) return;
    if (scale <= MIN_SCALE) { setScale(1); setTx(0); setTy(0); }
    // Двойной тап переключает зум (имеет смысл только для тапов — drag/pinch тоже попадает сюда и
    // неявно сбрасывает lastTap, но зазор > DOUBLE_TAP_MS до следующего реального тапа их разделяет).
    const now = Date.now();
    if (now - lastTap.current < DOUBLE_TAP_MS) {
      lastTap.current = 0;
      if (scale > 1) resetZoom();
      else setScale(DOUBLE_TAP_SCALE);
    } else {
      lastTap.current = now;
    }
  };

  // Удобство для десктопа/тестов: колесо мыши зумит в пределах MIN..MAX.
  const onWheel = (e: React.WheelEvent) => {
    const next = clamp(scale - Math.sign(e.deltaY) * 0.25, MIN_SCALE, MAX_SCALE);
    setScale(next);
    if (next <= MIN_SCALE) { setTx(0); setTy(0); }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={close}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 9999,
        background: 'rgba(0, 0, 0, 0.94)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        overflow: 'hidden',
      }}
    >
      <button
        type="button"
        aria-label="Закрыть"
        onClick={(e) => { e.stopPropagation(); close(); }}
        style={{
          position: 'fixed',
          top: 'max(12px, env(safe-area-inset-top))',
          right: 16,
          width: 40,
          height: 40,
          borderRadius: '50%',
          border: 'none',
          background: 'rgba(0, 0, 0, 0.5)',
          color: '#fff',
          fontSize: 22,
          lineHeight: '40px',
          cursor: 'pointer',
          zIndex: 1,
        }}
      >
        ✕
      </button>
      <img
        src={src}
        alt={alt}
        onClick={(e) => e.stopPropagation()}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onWheel={onWheel}
        draggable={false}
        style={{
          maxWidth: '100%',
          maxHeight: '100%',
          objectFit: 'contain',
          display: 'block',
          touchAction: 'none',
          transform: `translate(${tx}px, ${ty}px) scale(${scale})`,
          transformOrigin: 'center center',
          cursor: scale > 1 ? 'grab' : 'zoom-in',
        }}
      />
    </div>
  );
};
