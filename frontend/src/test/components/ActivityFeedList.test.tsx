import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { ActivityFeedList } from '../../components/manage/ActivityFeedList';
import type {
  ClubActivityFeed,
  EventActivityDto,
} from '../../api/activities';

function buildEvent(overrides: Partial<EventActivityDto> = {}): EventActivityDto {
  return {
    type: 'event',
    id: overrides.id ?? 'e-1',
    clubId: 'club-1',
    title: 'Yoga',
    createdAt: '2026-05-23T10:00:00Z',
    isCompleted: false,
    eventDatetime: '2026-05-30T11:00:00Z',
    locationText: 'Park',
    participantLimit: 20,
    goingCount: 5,
    status: 'upcoming',
    descriptionPreview: null,
    photoUrl: null,
    ...overrides,
  };
}

describe('ActivityFeedList', () => {
  it('renders the ПРЕДСТОЯЩИЕ section for upcoming activities', () => {
    const feed: ClubActivityFeed = {
      upcoming: [buildEvent({ id: 'u-1', title: 'Upcoming yoga' })],
      past: [],
    };
    render(<ActivityFeedList feed={feed} onActivityClick={vi.fn()} />);
    expect(screen.getByText('Предстоящие')).toBeInTheDocument();
    expect(screen.getByText('Upcoming yoga')).toBeInTheDocument();
  });

  it('animates the "Прошедшие (N)" accordion open via the grid wrapper (open class + hidden toggle)', async () => {
    const user = userEvent.setup();
    const feed: ClubActivityFeed = {
      upcoming: [],
      past: [
        buildEvent({ id: 'p-1', title: 'Old yoga', isCompleted: true, status: 'completed' }),
        buildEvent({ id: 'p-2', title: 'Older yoga', isCompleted: true, status: 'completed' }),
      ],
    };
    const { container } = render(
      <ActivityFeedList feed={feed} onActivityClick={vi.fn()} />,
    );

    // Collapsed by default: rows stay mounted (for the smooth transition) but the
    // wrapper has no `open` class and the list is hidden from the a11y tree.
    expect(screen.getByText('Прошедшие')).toBeInTheDocument();
    expect(screen.getByText('(2)')).toBeInTheDocument();
    const body = container.querySelector('.activity-past-body');
    expect(body).not.toBeNull();
    expect(body?.classList.contains('open')).toBe(false);
    expect(container.querySelector('.activity-list.compact')?.hasAttribute('hidden')).toBe(true);

    await user.click(screen.getByRole('button', { name: /прошедшие/i }));

    // Expanded: wrapper gets `open`, rows become visible, and stay in the DOM.
    expect(container.querySelector('.activity-past-body')?.classList.contains('open')).toBe(true);
    expect(container.querySelector('.activity-list.compact')?.hasAttribute('hidden')).toBe(false);
    expect(container.querySelectorAll('.activity-compact-row')).toHaveLength(2);
    expect(screen.getByText('Old yoga')).toBeInTheDocument();
  });

  it('fires onActivityClick with the tapped past activity after expanding', async () => {
    const user = userEvent.setup();
    const onActivityClick = vi.fn();
    const past = buildEvent({ id: 'p-1', title: 'Old yoga', isCompleted: true });
    const feed: ClubActivityFeed = { upcoming: [], past: [past] };

    render(<ActivityFeedList feed={feed} onActivityClick={onActivityClick} />);
    await user.click(screen.getByRole('button', { name: /прошедшие/i }));
    await user.click(screen.getByRole('button', { name: /old yoga/i }));

    expect(onActivityClick).toHaveBeenCalledWith(past);
  });

  it('omits the past accordion when there are no past activities', () => {
    const feed: ClubActivityFeed = {
      upcoming: [buildEvent()],
      past: [],
    };
    render(<ActivityFeedList feed={feed} onActivityClick={vi.fn()} />);
    expect(screen.queryByText('Прошедшие')).toBeNull();
  });
});
