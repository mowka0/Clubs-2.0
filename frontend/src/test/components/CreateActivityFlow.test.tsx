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
  return (
    <>
      <div data-testid="location">{loc.pathname}</div>
      <div data-testid="location-search">{loc.search}</div>
    </>
  );
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
      <Route path="/clubs/:id/skladchina/split" element={<LocationProbe />} />
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

    // «Событие» разветвляется на шаг формата (с местами / открытая встреча, PO 2026-07-21).
    expect(screen.getByText('Формат события')).toBeInTheDocument();
    await user.click(screen.getByText('С местами'));

    // No club-selection step — straight to the per-club create route.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
  });

  it('Событие → «Открытая встреча» ведёт на форму с ?format=open', async () => {
    const { user } = renderFlow(ONE_CLUB);

    await user.click(screen.getByText('Событие'));
    await user.click(screen.getByText('Открытая встреча'));

    // Маршрут тот же, формат передаётся query-параметром — CreateEventPage прячет степпер лимита.
    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-1/events/new');
    expect(screen.getByTestId('location-search').textContent).toBe('?format=open');
  });

  it('Сбор → template step → club picker → custom create route', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сбор'));

    // New step: pick the skladchina template before the club.
    expect(screen.getByText('Разделить счёт')).toBeInTheDocument();
    await user.click(screen.getByText('Свой сбор'));

    // Then the club picker appears with all organizer clubs.
    expect(screen.getByText('Выберите клуб')).toBeInTheDocument();
    expect(screen.getByText('Alpha Club')).toBeInTheDocument();
    await user.click(screen.getByText('Beta Club'));

    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/skladchina/new');
  });

  it('Сбор → «Разделить счёт» routes to the split-bill page', async () => {
    const { user } = renderFlow(TWO_CLUBS);

    await user.click(screen.getByText('Сбор'));
    await user.click(screen.getByText('Разделить счёт'));
    await user.click(screen.getByText('Beta Club'));

    expect(screen.getByTestId('location').textContent).toBe('/clubs/club-2/skladchina/split');
  });
});
