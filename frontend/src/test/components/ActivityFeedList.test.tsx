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

  it('toggles the "Прошедшие (N)" accordion via aria-expanded', async () => {
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

    // Collapsed by default (redesign): the rd-rep-panel with the rows is not
    // mounted; only the toggle button (aria-expanded=false) and the count show.
    const toggle = screen.getByRole('button', { name: /прошедшие/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('(2)')).toBeInTheDocument();
    expect(container.querySelector('.rd-rep-panel')).toBeNull();
    expect(screen.queryByText('Old yoga')).toBeNull();

    await user.click(toggle);

    // Expanded: the panel mounts with the compact rd-rep-row rows.
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const panel = container.querySelector('.rd-rep-panel');
    expect(panel).not.toBeNull();
    expect(panel?.querySelectorAll('.rd-rep-row')).toHaveLength(2);
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
