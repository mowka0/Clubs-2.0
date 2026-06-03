import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Stub the heavy create-flow modal — we only assert whether it opens.
vi.mock('../../components/manage/CreateActivityFlow', () => ({
  CreateActivityFlow: ({ open }: { open: boolean }) =>
    open ? <div data-testid="create-flow" /> : null,
}));

// Control organizer detection directly — the visibility rule is the unit under test.
const useOrganizerClubsMock = vi.fn();
vi.mock('../../queries/organizerClubs', () => ({
  useOrganizerClubs: () => useOrganizerClubsMock(),
}));

import { AppDock } from '../../components/Layout';
import { renderWithProviders } from '../utils/renderWithProviders';

beforeEach(() => {
  useOrganizerClubsMock.mockReset();
});

// The Banco redesign moved the "create activity" entry point from an in-page
// hero button on ActivitiesPage to the dock's always-visible FAB. The organizer
// guardrail now lives in AppDock: organizers open the flow, everyone else gets
// an explanatory toast.
describe('AppDock — FAB "create activity" guardrail', () => {
  it('shows a toast (and no create flow) when the user organizes no clubs', async () => {
    const user = userEvent.setup();
    useOrganizerClubsMock.mockReturnValue({ clubs: [], isLoading: false });
    renderWithProviders(<AppDock />, { routerEntries: ['/events'] });

    await user.click(screen.getByRole('button', { name: /создать/i }));

    expect(screen.getByText(/организаторы клубов/i)).toBeInTheDocument();
    expect(screen.queryByTestId('create-flow')).toBeNull();
  });

  it('opens the create flow when the user organizes at least one club', async () => {
    const user = userEvent.setup();
    useOrganizerClubsMock.mockReturnValue({
      clubs: [{ id: 'club-1', name: 'Alpha', avatarUrl: null, category: 'sport' }],
      isLoading: false,
    });
    renderWithProviders(<AppDock />, { routerEntries: ['/events'] });

    await user.click(screen.getByRole('button', { name: /создать/i }));

    expect(screen.getByTestId('create-flow')).toBeInTheDocument();
  });
});
