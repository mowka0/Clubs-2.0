import { FC } from 'react';
import { useHaptic } from '../hooks/useHaptic';

interface BrandStepperProps {
  value: number;
  onChange: (next: number) => void;
  min?: number;
  max?: number;
  step?: number;
  ariaLabel?: string;
}

/**
 * Brand-styled numeric stepper: [−] value [+].
 *
 * Replaces the native `<input type="number">` spinner (which renders with
 * default browser chrome and clashes with the brand form). The middle value
 * is a read-only display — direct keyboard entry is intentionally omitted to
 * keep the control predictable across the Telegram WebView. Value is clamped
 * to [min, max] on every change. Haptic `selection` fires on each tap.
 */
export const BrandStepper: FC<BrandStepperProps> = ({
  value,
  onChange,
  min = 1,
  max = Number.MAX_SAFE_INTEGER,
  step = 1,
  ariaLabel,
}) => {
  const haptic = useHaptic();

  const clamp = (n: number): number => Math.min(max, Math.max(min, n));

  const handleDecrement = () => {
    const next = clamp(value - step);
    if (next === value) return;
    haptic.select();
    onChange(next);
  };

  const handleIncrement = () => {
    const next = clamp(value + step);
    if (next === value) return;
    haptic.select();
    onChange(next);
  };

  return (
    <div className="brand-stepper" role="group" aria-label={ariaLabel}>
      <button
        type="button"
        className="brand-stepper-btn"
        onClick={handleDecrement}
        disabled={value <= min}
        aria-label="Уменьшить"
      >
        −
      </button>
      <span className="brand-stepper-value" aria-live="polite">
        {value}
      </span>
      <button
        type="button"
        className="brand-stepper-btn"
        onClick={handleIncrement}
        disabled={value >= max}
        aria-label="Увеличить"
      >
        +
      </button>
    </div>
  );
};
