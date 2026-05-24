import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { BrandStepper } from '../../components/BrandStepper';

function Harness({ initial, min, max }: { initial: number; min?: number; max?: number }) {
  const [value, setValue] = useState(initial);
  return <BrandStepper value={value} onChange={setValue} min={min} max={max} />;
}

function getInput(): HTMLInputElement {
  return screen.getByRole('textbox') as HTMLInputElement;
}

describe('BrandStepper', () => {
  it('renders the current value', () => {
    render(<Harness initial={20} />);
    expect(getInput().value).toBe('20');
  });

  it('increments the value when + is tapped', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} />);
    await user.click(screen.getByRole('button', { name: 'Увеличить' }));
    expect(getInput().value).toBe('21');
  });

  it('decrements the value when − is tapped', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} />);
    await user.click(screen.getByRole('button', { name: 'Уменьшить' }));
    expect(getInput().value).toBe('19');
  });

  it('clamps at min and disables the decrement button there', async () => {
    const user = userEvent.setup();
    render(<Harness initial={1} min={1} />);
    const decrement = screen.getByRole('button', { name: 'Уменьшить' });
    expect(decrement).toBeDisabled();
    await user.click(decrement);
    expect(getInput().value).toBe('1');
  });

  it('clamps at max and disables the increment button there', async () => {
    const user = userEvent.setup();
    render(<Harness initial={5} max={5} />);
    const increment = screen.getByRole('button', { name: 'Увеличить' });
    expect(increment).toBeDisabled();
    await user.click(increment);
    expect(getInput().value).toBe('5');
  });

  it('accepts manual numeric entry and clamps to max on blur', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} min={1} max={50} />);
    const input = getInput();
    await user.clear(input);
    await user.type(input, '999');
    // While typing the draft is free; clamp happens on blur.
    expect(input.value).toBe('999');
    await user.tab();
    expect(input.value).toBe('50');
  });

  it('clamps manual entry up to min on blur', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} min={5} max={50} />);
    const input = getInput();
    await user.clear(input);
    await user.type(input, '2');
    await user.tab();
    expect(input.value).toBe('5');
  });

  it('treats an empty field as min on blur', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} min={3} max={50} />);
    const input = getInput();
    await user.clear(input);
    await user.tab();
    expect(input.value).toBe('3');
  });
});
