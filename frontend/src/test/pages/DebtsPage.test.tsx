import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { DebtsOverviewDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  mountBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  unmountBackButton: vi.fn(),
  showBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  hideBackButton: Object.assign(vi.fn(), { isAvailable: () => false }),
  onBackButtonClick: Object.assign(vi.fn(() => vi.fn()), { isAvailable: () => false }),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { DebtsPage } from '../../pages/DebtsPage';

const OVERVIEW: DebtsOverviewDto = {
  oweKopecks: 185700,
  owedKopecks: 40000,
  awaitingMyConfirmation: 1,
  people: [
    {
      user: { id: 'u-k', firstName: 'Кристина', lastName: null, username: 'kris', avatarUrl: null },
      balanceKopecks: -145700,
      oweKopecks: 185700,
      owedKopecks: 40000,
      debtCount: 3,
      nearestDueAt: '2026-09-20T18:00:00Z',
      awaitingMyConfirmation: 1,
      awaitingTheirConfirmation: 0,
    },
  ],
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/debts" element={<DebtsPage />} />
    </Routes>,
    { routerEntries: ['/debts'] },
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('DebtsPage — личная книга долгов', () => {
  it('показывает две цифры и плашку человека с сальдо и меткой «подтвердите»', async () => {
    server.use(http.get('*/api/debts', () => HttpResponse.json(OVERVIEW)));
    renderPage();

    expect(await screen.findByText('Кристина')).toBeInTheDocument();
    expect(screen.getByText('@kris')).toBeInTheDocument();
    expect(screen.getByText('Вы должны')).toBeInTheDocument();
    expect(screen.getByText(/1\s?857 ₽/)).toBeInTheDocument();
    expect(screen.getByText(/3 долга/)).toBeInTheDocument();
    expect(screen.getByText('говорит, что отдал · подтвердите')).toBeInTheDocument();
    expect(screen.getByText(/−1\s?457 ₽/)).toBeInTheDocument();
  });

  it('без долгов — «Долгов нет»', async () => {
    server.use(http.get('*/api/debts', () => HttpResponse.json({ oweKopecks: 0, owedKopecks: 0, awaitingMyConfirmation: 0, people: [] })));
    renderPage();
    expect(await screen.findByText('Долгов нет')).toBeInTheDocument();
  });
});
