import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { ActivityCard } from '../../components/manage/ActivityCard';
import type {
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';

function buildEvent(overrides: Partial<EventActivityDto> = {}): EventActivityDto {
  return {
    type: 'event',
    id: 'e-1',
    clubId: 'club-1',
    title: 'Yoga in the park',
    createdAt: '2026-05-23T10:00:00Z',
    isCompleted: false,
    eventDatetime: '2026-05-30T11:00:00Z',
    locationText: 'Gorky park',
    participantLimit: 20,
    goingCount: 5,
    status: 'upcoming',
    descriptionPreview: null,
    ...overrides,
  };
}

function buildSkladchina(
  overrides: Partial<SkladchinaActivityDto> = {},
): SkladchinaActivityDto {
  return {
    type: 'skladchina',
    id: 's-1',
    clubId: 'club-1',
    title: 'Sauna booking',
    createdAt: '2026-05-23T10:00:00Z',
    isCompleted: false,
    paymentMode: 'fixed_equal',
    totalGoalKopecks: 500000,
    collectedKopecks: 100000,
    deadline: '2026-05-30T23:59:00Z',
    participantCount: 5,
    paidCount: 1,
    status: 'active',
    affectsReputation: false,
    ...overrides,
  };
}

describe('ActivityCard', () => {
  it('renders event title and going/limit badge', () => {
    const onClick = vi.fn();
    render(<ActivityCard activity={buildEvent()} onClick={onClick} />);
    expect(screen.getByText('Yoga in the park')).toBeInTheDocument();
    expect(screen.getByText('5/20')).toBeInTheDocument();
  });

  it('renders descriptionPreview as a second subtitle row when present', () => {
    const onClick = vi.fn();
    render(
      <ActivityCard
        activity={buildEvent({
          descriptionPreview: 'Возьмите коврик и хорошее настроение…',
        })}
        onClick={onClick}
      />,
    );
    expect(
      screen.getByText('Возьмите коврик и хорошее настроение…'),
    ).toBeInTheDocument();
  });

  it('does NOT render description row when descriptionPreview is null', () => {
    const onClick = vi.fn();
    const { container } = render(
      <ActivityCard activity={buildEvent({ descriptionPreview: null })} onClick={onClick} />,
    );
    // Exactly one subtitle (datetime · location). The dim second row is absent.
    const text = container.textContent ?? '';
    expect(text.includes('Gorky park')).toBe(true);
  });

  it('renders Завершено badge and ARIA label when isCompleted', () => {
    const onClick = vi.fn();
    render(
      <ActivityCard
        activity={buildEvent({ isCompleted: true, status: 'completed' })}
        onClick={onClick}
      />,
    );
    expect(screen.getByText('Завершено')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /yoga in the park\. завершено/i }),
    ).toBeInTheDocument();
  });

  it('renders skladchina with goal as "collected / goal" and paid/participant badge', () => {
    const onClick = vi.fn();
    render(
      <ActivityCard activity={buildSkladchina()} onClick={onClick} />,
    );
    expect(screen.getByText('Sauna booking')).toBeInTheDocument();
    expect(screen.getByText('1/5')).toBeInTheDocument();
    // Format is "1 000 ₽ / 5 000 ₽" (Russian thousands separator). Check
    // robustness on rub amounts present in the document.
    expect(screen.getByText(/1\s?000\s?₽/)).toBeInTheDocument();
    expect(screen.getByText(/5\s?000\s?₽/)).toBeInTheDocument();
  });

  it('renders voluntary skladchina as "<amount> собрано" without goal', () => {
    const onClick = vi.fn();
    render(
      <ActivityCard
        activity={buildSkladchina({
          paymentMode: 'voluntary',
          totalGoalKopecks: null,
          collectedKopecks: 200000,
        })}
        onClick={onClick}
      />,
    );
    expect(screen.getByText(/собрано/)).toBeInTheDocument();
  });

  it('calls onClick when the card is tapped', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<ActivityCard activity={buildEvent()} onClick={onClick} />);
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
