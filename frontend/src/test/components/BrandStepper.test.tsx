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

describe('BrandStepper', () => {
  it('renders the current value', () => {
    render(<Harness initial={20} />);
    expect(screen.getByText('20')).toBeInTheDocument();
  });

  it('increments the value when + is tapped', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} />);
    await user.click(screen.getByRole('button', { name: 'Увеличить' }));
    expect(screen.getByText('21')).toBeInTheDocument();
  });

  it('decrements the value when − is tapped', async () => {
    const user = userEvent.setup();
    render(<Harness initial={20} />);
    await user.click(screen.getByRole('button', { name: 'Уменьшить' }));
    expect(screen.getByText('19')).toBeInTheDocument();
  });

  it('clamps at min and disables the decrement button there', async () => {
    const user = userEvent.setup();
    render(<Harness initial={1} min={1} />);
    const decrement = screen.getByRole('button', { name: 'Уменьшить' });
    expect(decrement).toBeDisabled();
    await user.click(decrement);
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('clamps at max and disables the increment button there', async () => {
    const user = userEvent.setup();
    render(<Harness initial={5} max={5} />);
    const increment = screen.getByRole('button', { name: 'Увеличить' });
    expect(increment).toBeDisabled();
    await user.click(increment);
    expect(screen.getByText('5')).toBeInTheDocument();
  });
});
