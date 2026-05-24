import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// The segment tabs render the heavy feed tabs — stub them so this test focuses
// purely on the hero "+" guardrail (organizer detection).
vi.mock('../../components/activities/EventsTab', () => ({
  EventsTab: () => <div data-testid="events-tab" />,
}));
vi.mock('../../components/activities/SkladchinasTab', () => ({
  SkladchinasTab: () => <div data-testid="skladchinas-tab" />,
}));

// Control organizer detection directly — the visibility rule is the unit under test.
const useOrganizerClubsMock = vi.fn();
vi.mock('../../queries/organizerClubs', () => ({
  useOrganizerClubs: () => useOrganizerClubsMock(),
}));

import { ActivitiesPage } from '../../pages/ActivitiesPage';
import { renderWithProviders } from '../utils/renderWithProviders';

beforeEach(() => {
  useOrganizerClubsMock.mockReset();
});

describe('ActivitiesPage — global "+" create button', () => {
  it('hides the "+" when the user organizes no clubs', () => {
    useOrganizerClubsMock.mockReturnValue({ clubs: [], isLoading: false });
    renderWithProviders(<ActivitiesPage />, { routerEntries: ['/events'] });
    expect(screen.queryByRole('button', { name: /создать/i })).toBeNull();
  });

  it('shows the "+" when the user organizes at least one club', () => {
    useOrganizerClubsMock.mockReturnValue({
      clubs: [{ id: 'club-1', name: 'Alpha', avatarUrl: null, category: 'sport' }],
      isLoading: false,
    });
    renderWithProviders(<ActivitiesPage />, { routerEntries: ['/events'] });
    expect(screen.getByRole('button', { name: /создать/i })).toBeInTheDocument();
  });
});
