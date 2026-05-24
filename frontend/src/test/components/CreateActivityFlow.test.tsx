import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';

vi.mock('@telegram-apps/sdk-react', () => ({
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

import { CreateActivityFlow } from '../../components/manage/CreateActivityFlow';
import type { ClubPickerOption } from '../../components/manage/ClubPickerModal';
import { renderWithProviders } from '../utils/renderWithProviders';

const ONE_CLUB: ClubPickerOption[] = [
  { id: 'club-1', name: 'Alpha Club', avatarUrl: null, category: 'sport' },
];
const TWO_CLUBS: ClubPickerOption[] = [
  { id: 'club-1', name: 'Alpha Club', avatarUrl: null, category: 'sport' },
  { id: 'club-2', name: 'Beta Club', avatarUrl: null, category: 'food' },
];

const LocationProbe = () => {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname}</div>;
};

function renderFlow(clubs: ClubPickerOption[]) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <>
            <CreateActivityFlow open organizerClubs={clubs} onClose={vi.fn()} />
            <LocationProbe />
          </>
        }
      />
      <Route path="/clubs/:id/events/new" element={<LocationProbe />} />
      <Route path="/clubs/:id/skladchina/new" element={<LocationProbe />} />
    </Routes>,
    { routerEntries: ['/'] },
  );
  return { ...result, user };
}

describe('CreateActivityFlow', () => {
  it('auto-selects the single organizer club and navigates straight to the create route', async () => {
    const { user } = renderFlow(ONE_CLUB);

    // Step 1: type picker is shown; the club picker is not.
    expect(screen.getByText('Событие')).toBeInTheDocument();
    expect(screen.queryByText('Выберите клуб')).toBeNull();

    await user.click(screen.getByText('Событие'));

    // No club-selection step — straight to the per-club create route.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
  });

  it('shows a club picker when the user organizes multiple clubs, then navigates to the picked club', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сбор'));

    // Step 2: club picker appears with all organizer clubs.
    expect(screen.getByText('Выберите клуб')).toBeInTheDocument();
    expect(screen.getByText('Alpha Club')).toBeInTheDocument();
    expect(screen.getByText('Beta Club')).toBeInTheDocument();

    await user.click(screen.getByText('Beta Club'));

    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/skladchina/new');
  });
});
