import { ChangeEvent, FC, FocusEvent, useEffect, useState } from 'react';
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
 * default browser chrome and clashes with the brand form). The middle value is
 * an editable numeric input: while typing it accepts free input (including a
 * transient empty string), and clamps to [min, max] on blur. The +/− buttons
 * adjust by `step` and clamp on every tap. Haptic `selection` fires only on
 * +/− taps, not per keystroke. Value is always reported to the parent as a
 * `number` within [min, max].
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

  // Local draft lets the user clear the field and type freely; the committed
  // numeric value (`value`) stays the source of truth and is re-synced here
  // whenever it changes from outside (buttons, parent reset).
  const [draft, setDraft] = useState<string>(String(value));

  useEffect(() => {
    setDraft(String(value));
  }, [value]);

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

  const handleInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value;
    // Permit only digits while typing; empty string allowed transiently.
    if (raw === '' || /^\d+$/.test(raw)) {
      setDraft(raw);
    }
  };

  const handleBlur = (_e: FocusEvent<HTMLInputElement>) => {
    const parsed = Number.parseInt(draft, 10);
    const next = Number.isNaN(parsed) ? min : clamp(parsed);
    setDraft(String(next));
    if (next !== value) onChange(next);
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
      <input
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        className="brand-stepper-value"
        value={draft}
        onChange={handleInputChange}
        onBlur={handleBlur}
        aria-label={ariaLabel ?? 'Значение'}
      />
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
