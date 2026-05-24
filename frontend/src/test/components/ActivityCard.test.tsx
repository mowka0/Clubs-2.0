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
    goingCount: 5,
    status: 'upcoming',
    descriptionPreview: null,
    photoUrl: null,
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
  it('renders event title and going/limit badge', () => {
    render(<ActivityCard activity={buildEvent()} onClick={vi.fn()} />);
    expect(screen.getByText('Yoga in the park')).toBeInTheDocument();
    expect(screen.getByText('5/20')).toBeInTheDocument();
  });

  it('puts the type icon in the top-right badge (not inline with the title)', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent()} onClick={vi.fn()} />,
    );
    const badge = container.querySelector('.activity-card .type-badge');
    expect(badge?.textContent).toBe('🗓');
    // Title lives in the middle content column and carries no leading emoji.
    const title = container.querySelector('.activity-card .content .title');
    expect(title?.textContent).toBe('Yoga in the park');
  });

  it('renders a placeholder thumb (with type emoji) when photoUrl is absent', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent({ photoUrl: null })} onClick={vi.fn()} />,
    );
    const thumb = container.querySelector('.activity-card .activity-thumb');
    expect(thumb).not.toBeNull();
    expect(thumb?.classList.contains('placeholder')).toBe(true);
    expect(thumb?.querySelector('img')).toBeNull();
    expect(thumb?.querySelector('.placeholder-emoji')?.textContent).toBe('🗓');
  });

  it('renders the photo (cover img) on the left when photoUrl is present', () => {
    const { container } = render(
      <ActivityCard
        activity={buildEvent({ photoUrl: 'https://cdn.example/e.jpg' })}
        onClick={vi.fn()}
      />,
    );
    const thumb = container.querySelector('.activity-card .activity-thumb');
    expect(thumb?.classList.contains('placeholder')).toBe(false);
    const img = thumb?.querySelector('img');
    expect(img?.getAttribute('src')).toBe('https://cdn.example/e.jpg');
  });

  it('uses the 💰 emoji placeholder for a photoless skladchina', () => {
    const { container } = render(
      <ActivityCard activity={buildSkladchina({ photoUrl: null })} onClick={vi.fn()} />,
    );
    const thumb = container.querySelector('.activity-card .activity-thumb.placeholder');
    expect(thumb?.querySelector('.placeholder-emoji')?.textContent).toBe('💰');
    expect(container.querySelector('.activity-card .type-badge')?.textContent).toBe('💰');
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

  it('does NOT render description row when descriptionPreview is null', () => {
    const { container } = render(
      <ActivityCard activity={buildEvent({ descriptionPreview: null })} onClick={vi.fn()} />,
    );
    expect(container.querySelector('.sub.dim')).toBeNull();
  });

  it('applies completed style + ARIA label and shows Завершено badge', () => {
    const { container } = render(
      <ActivityCard
        activity={buildEvent({ isCompleted: true, status: 'completed' })}
        onClick={vi.fn()}
      />,
    );
    expect(container.querySelector('.activity-card.completed')).not.toBeNull();
    expect(screen.getByText('Завершено')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /yoga in the park\. завершено/i }),
    ).toBeInTheDocument();
  });

  it('renders skladchina with goal as "collected / goal" and paid/participant badge', () => {
    render(<ActivityCard activity={buildSkladchina()} onClick={vi.fn()} />);
    expect(screen.getByText('Sauna booking')).toBeInTheDocument();
    expect(screen.getByText('1/5')).toBeInTheDocument();
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

  it('renders ⚠️ Репутация when affectsReputation', () => {
    render(
      <ActivityCard
        activity={buildSkladchina({ affectsReputation: true })}
        onClick={vi.fn()}
      />,
    );
    expect(screen.getByText(/Репутация/)).toBeInTheDocument();
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
  it('renders a single-line dim row with icon, title and event short date', () => {
    const { container } = render(
      <ActivityCompactRow
        activity={buildEvent({ isCompleted: true })}
        onClick={vi.fn()}
      />,
    );
    const row = container.querySelector('.activity-compact-row');
    expect(row).not.toBeNull();
    expect(row?.querySelector('.ico')?.textContent).toBe('🗓');
    expect(row?.querySelector('.title')?.textContent).toBe('Yoga in the park');
    expect(row?.querySelector('.date')?.textContent).toBe(
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
    expect(container.querySelector('.activity-compact-row .ico')?.textContent).toBe('💰');
    expect(container.querySelector('.activity-compact-row .date')?.textContent).toBe(
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
