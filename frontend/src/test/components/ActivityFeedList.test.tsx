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
    minParticipants: null,
    format: 'normal',
    goingCount: 5,
    confirmedCount: 0,
    status: 'upcoming',
    descriptionPreview: null,
    photoUrl: null,
    actionRequired: false,
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

  it('три последних прошедших видны сразу, хвост — за «Показать все» (PO 2026-09-14)', async () => {
    const user = userEvent.setup();
    const feed: ClubActivityFeed = {
      upcoming: [],
      past: [1, 2, 3, 4, 5].map((n) => buildEvent({
        id: `p-${n}`, title: `Old yoga ${n}`, isCompleted: true, status: 'completed',
      })),
    };
    const { container } = render(<ActivityFeedList feed={feed} onActivityClick={vi.fn()} />);

    // Ярлык секции — такой же, как «Предстоящие», а не кнопка-шторка.
    expect(screen.getByText('Прошедшие')).toBeInTheDocument();
    expect(screen.getByText('· 5')).toBeInTheDocument();
    expect(container.querySelectorAll('.rd-rep-row')).toHaveLength(3);
    expect(screen.getByText('Old yoga 3')).toBeInTheDocument();
    expect(screen.queryByText('Old yoga 4')).toBeNull();

    const more = screen.getByRole('button', { name: /Показать все · 5/ });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    await user.click(more);

    expect(container.querySelectorAll('.rd-rep-row')).toHaveLength(5);
    expect(screen.getByText('Old yoga 5')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Свернуть' }));
    expect(container.querySelectorAll('.rd-rep-row')).toHaveLength(3);
  });

  it('три и меньше прошедших — кнопки «Показать все» нет', () => {
    const feed: ClubActivityFeed = {
      upcoming: [],
      past: [buildEvent({ id: 'p-1', title: 'Old yoga', isCompleted: true, status: 'completed' })],
    };
    render(<ActivityFeedList feed={feed} onActivityClick={vi.fn()} />);

    expect(screen.getByText('Old yoga')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Показать все/ })).toBeNull();
  });

  it('fires onActivityClick with the tapped past activity', async () => {
    const user = userEvent.setup();
    const onActivityClick = vi.fn();
    const past = buildEvent({ id: 'p-1', title: 'Old yoga', isCompleted: true });
    const feed: ClubActivityFeed = { upcoming: [], past: [past] };

    render(<ActivityFeedList feed={feed} onActivityClick={onActivityClick} />);
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
