import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { ActivityCard } from '../../components/manage/ActivityCard';
import { ActivityCompactRow } from '../../components/manage/ActivityCompactRow';
import type {
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';

// Mirror the component's short-date formatter so date assertions are
// timezone-independent (no hardcoded day that shifts across UTC offsets).
const SHORT_DATE_FMT = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'short',
});
function shortDate(iso: string): string {
  return SHORT_DATE_FMT.format(new Date(iso));
}

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
    isUrgent: false,
    goingCount: 5,
    confirmedCount: 0,
    status: 'upcoming',
    descriptionPreview: null,
    photoUrl: null,
    actionRequired: false,
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
    deadline: '2026-05-28T12:00:00Z',
    participantCount: 5,
    paidCount: 1,
    status: 'active',
    affectsReputation: false,
    photoUrl: null,
    ...overrides,
  };
}

describe('ActivityCard (full)', () => {
  it('renders event title and the going/limit fraction with caption', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent()} onClick={vi.fn()} />,
    );
    expect(screen.getByText('Yoga in the park')).toBeInTheDocument();
    // going count shown as a "going/limit" gradient fraction.
    expect(container.querySelector('.rd-ft-stat-num')?.textContent).toBe('5/20');
    expect(container.querySelector('.rd-ft-stat-cap')?.textContent).toBe('идёт');
  });

  // Ярлыки формата PO 2026-07-23: срочная/обычная/открытая с эмодзи пикера.
  it('shows the format badge: обычная / открытая / срочная', () => {
    const { rerender } = render(<ActivityCard activity={buildEvent()} onClick={vi.fn()} />);
    expect(screen.getByText('🎟 Обычная')).toBeInTheDocument();

    rerender(<ActivityCard activity={buildEvent({ participantLimit: null })} onClick={vi.fn()} />);
    expect(screen.getByText('🌊 Открытая')).toBeInTheDocument();

    rerender(<ActivityCard activity={buildEvent({ isUrgent: true, status: 'stage_2' })} onClick={vi.fn()} />);
    expect(screen.getByText('⚡ Срочная')).toBeInTheDocument();
  });

  it('switches to confirmedCount/"подтв." once voting closes (stage_2)', () => {
    // F5-21: after stage 1 the roster is the confirmed list — the card must match the event
    // page (confirmedCount), not keep showing the stale stage-1 goingCount.
    const { container } = render(
      <ActivityCard
        activity={buildEvent({ status: 'stage_2', goingCount: 5, confirmedCount: 2 })}
        onClick={vi.fn()}
      />,
    );
    expect(container.querySelector('.rd-ft-stat-num')?.textContent).toBe('2/20');
    expect(container.querySelector('.rd-ft-stat-cap')?.textContent).toBe('подтв.');
  });

  it('uses confirmedCount for a completed event', () => {
    const { container } = render(
      <ActivityCard
        activity={buildEvent({ status: 'completed', isCompleted: true, goingCount: 9, confirmedCount: 6 })}
        onClick={vi.fn()}
      />,
    );
    expect(container.querySelector('.rd-ft-stat-num')?.textContent).toBe('6/20');
    expect(container.querySelector('.rd-ft-stat-cap')?.textContent).toBe('подтв.');
  });

  it('shows "Проголосуй" badge when action is required (stage-1 vote pending)', () => {
    render(
      <ActivityCard activity={buildEvent({ actionRequired: true, status: 'upcoming' })} onClick={vi.fn()} />,
    );
    expect(screen.getByText('Проголосуй')).toBeInTheDocument();
  });

  it('shows "Подтверди участие" badge when action is required in stage 2', () => {
    render(
      <ActivityCard activity={buildEvent({ actionRequired: true, status: 'stage_2' })} onClick={vi.fn()} />,
    );
    expect(screen.getByText('Подтверди участие')).toBeInTheDocument();
  });

  it('shows no action badge when nothing is required', () => {
    render(
      <ActivityCard activity={buildEvent({ actionRequired: false })} onClick={vi.fn()} />,
    );
    expect(screen.queryByText('Проголосуй')).toBeNull();
    expect(screen.queryByText('Подтверди участие')).toBeNull();
  });

  it('is a text-only rd-feature card — no thumb, photo or type emoji', () => {
    const { container } = render(
      <ActivityCard
        activity={buildEvent({ photoUrl: 'https://cdn.example/e.jpg' })}
        onClick={vi.fn()}
      />,
    );
    // The Banco redesign dropped the left thumbnail and the emoji type-badge.
    expect(container.querySelector('.activity-thumb')).toBeNull();
    expect(container.querySelector('.type-badge')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    const title = container.querySelector('.rd-feature .rd-ft-title');
    expect(title?.textContent).toBe('Yoga in the park');
  });

  it('shows event date/time and location, not the creation date', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent()} onClick={vi.fn()} />,
    );
    const text = container.textContent ?? '';
    expect(text).toContain('Gorky park');
    // event date present
    expect(text).toContain(shortDate('2026-05-30T11:00:00Z'));
    // creation date (23 May) NOT present
    expect(text).not.toContain(shortDate('2026-05-23T10:00:00Z'));
  });

  it('renders descriptionPreview as a dim subtitle row when present', () => {
    render(
      <ActivityCard
        activity={buildEvent({
          descriptionPreview: 'Возьмите коврик и хорошее настроение…',
        })}
        onClick={vi.fn()}
      />,
    );
    expect(
      screen.getByText('Возьмите коврик и хорошее настроение…'),
    ).toBeInTheDocument();
  });

  it('renders only one rd-ft-sub row when descriptionPreview is null', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent({ descriptionPreview: null })} onClick={vi.fn()} />,
    );
    // Event always renders the date/location sub; description adds a second one.
    expect(container.querySelectorAll('.rd-ft-sub')).toHaveLength(1);
  });

  it('dims completed activities and keeps the «Завершено» aria-label', () => {
    render(
      <ActivityCard
        activity={buildEvent({ isCompleted: true, status: 'completed' })}
        onClick={vi.fn()}
      />,
    );
    // Redesign dims via inline opacity (no `.completed` class, no visible badge).
    const btn = screen.getByRole('button', { name: /yoga in the park\. завершено/i });
    expect(btn).toBeInTheDocument();
    expect((btn as HTMLButtonElement).style.opacity).toBe('0.6');
  });

  it('renders skladchina with goal as "collected / goal" and percent collected', () => {
    const { container } = render(<ActivityCard activity={buildSkladchina()} onClick={vi.fn()} />);
    expect(screen.getByText('Sauna booking')).toBeInTheDocument();
    // 100000 / 500000 kopecks = 20% collected.
    expect(container.querySelector('.rd-ft-stat-num')?.textContent).toBe('20%');
    expect(container.querySelector('.rd-ft-stat-cap')?.textContent).toBe('собрано');
    expect(screen.getByText(/1\s?000\s?₽/)).toBeInTheDocument();
    expect(screen.getByText(/5\s?000\s?₽/)).toBeInTheDocument();
  });

  it('renders voluntary skladchina as "<amount> собрано" without goal', () => {
    render(
      <ActivityCard
        activity={buildSkladchina({
          paymentMode: 'voluntary',
          totalGoalKopecks: null,
          collectedKopecks: 200000,
        })}
        onClick={vi.fn()}
      />,
    );
    expect(screen.getByText(/собрано/)).toBeInTheDocument();
  });

  it('renders ⚠️ Важный сбор when affectsReputation', () => {
    render(
      <ActivityCard
        activity={buildSkladchina({ affectsReputation: true })}
        onClick={vi.fn()}
      />,
    );
    expect(screen.getByText(/Важный сбор/)).toBeInTheDocument();
  });

  it('calls onClick when the card is tapped', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<ActivityCard activity={buildEvent()} onClick={onClick} />);
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

describe('ActivityCompactRow (past)', () => {
  it('renders a compact rd-rep-row with title and event short date', () => {
    const { container } = render(
      <ActivityCompactRow
        activity={buildEvent({ isCompleted: true })}
        onClick={vi.fn()}
      />,
    );
    const row = container.querySelector('.rd-rep-row');
    expect(row).not.toBeNull();
    expect(row?.querySelector('.rd-ttl')?.textContent).toBe('Yoga in the park');
    expect(row?.querySelector('.rd-cap')?.textContent).toBe(
      shortDate('2026-05-30T11:00:00Z'),
    );
  });

  it('uses the skladchina deadline as the short date', () => {
    const { container } = render(
      <ActivityCompactRow
        activity={buildSkladchina({ isCompleted: true })}
        onClick={vi.fn()}
      />,
    );
    expect(container.querySelector('.rd-rep-row .rd-cap')?.textContent).toBe(
      shortDate('2026-05-28T12:00:00Z'),
    );
  });

  it('calls onClick when tapped', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <ActivityCompactRow
        activity={buildEvent({ isCompleted: true })}
        onClick={onClick}
      />,
    );
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
