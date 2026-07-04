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
 * Числовой степпер в стиле бренда: [−] значение [+].
 *
 * Заменяет нативный спиннер `<input type="number">` (рендерится с дефолтным
 * браузерным хромом и не вписывается в брендовую форму). Среднее значение —
 * редактируемое числовое поле: во время ввода принимает свободный ввод
 * (включая временную пустую строку), а на blur зажимается в [min, max].
 * Кнопки +/− изменяют значение на `step` и зажимают его при каждом тапе.
 * Haptic `selection` срабатывает только на тапах +/−, не на каждое нажатие
 * клавиши. Родителю значение всегда передаётся как `number` в пределах [min, max].
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

  // Локальный черновик позволяет пользователю очистить поле и печатать свободно;
  // зафиксированное числовое значение (`value`) остаётся источником истины
  // и пересинхронизируется здесь при каждом внешнем изменении (кнопки, сброс родителем).
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
    // Разрешаем только цифры во время ввода; пустая строка допустима временно.
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
