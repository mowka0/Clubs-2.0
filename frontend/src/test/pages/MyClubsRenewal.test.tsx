import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { MembershipDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  backButton: { show: vi.fn(), hide: vi.fn(), onClick: vi.fn(() => vi.fn()) },
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

import { MyClubsPage } from '../../pages/MyClubsPage';
import { useAuthStore } from '../../store/useAuthStore';

const VIEWER_ID = 'viewer-1';
const CLUB_ID = 'club-1';
const DAY = 86_400_000;
const inDays = (n: number) => new Date(Date.now() + n * DAY).toISOString();

/** Собственное active-членство вызывающего в платном клубе с задаваемым концом подписки. */
function membership(over: Partial<MembershipDto> = {}): MembershipDto {
  return {
    id: 'm-1',
    userId: VIEWER_ID,
    clubId: CLUB_ID,
    status: 'active',
    role: 'member',
    joinedAt: '2026-06-01T10:00:00Z',
    subscriptionExpiresAt: inDays(2),
    duesClaimedAt: null,
    duesClaimMethod: null,
    ...over,
  } as MembershipDto;
}

function mockEndpoints(m: MembershipDto) {
  server.use(
    http.get('*/api/users/me/clubs', () => HttpResponse.json([m])),
    http.get('*/api/users/me/applications', () => HttpResponse.json([])),
    http.get('*/api/users/me/applications-pending', () => HttpResponse.json([])),
    http.get('*/api/users/me/reputation', () => HttpResponse.json({
      global: { reliableClubs: 0, trackRecordClubs: 0, score: null },
      activeClubs: [],
      historyClubs: [],
    })),
    http.get(`*/api/clubs/${CLUB_ID}/organizer-card`, () => HttpResponse.json({
      firstName: 'Орг', lastName: null, username: 'org', avatarUrl: null,
      onPlatformSince: '2025-01-01T00:00:00Z', clubsCount: 1, trustedMembers: 0,
    })),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID, ownerId: 'someone-else', name: 'Шахматы', description: 'd', category: 'board_games',
      accessType: 'open', city: 'Москва', district: null, memberLimit: 20, subscriptionPrice: 500,
      paymentLink: '+79990000000', paymentMethodNote: null,
      avatarUrl: null, rules: null, applicationQuestion: null, inviteLink: null, memberCount: 5, isActive: true,
    })),
  );
}

function renderPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/my-clubs" element={<MyClubsPage />} />
    </Routes>,
    { routerEntries: ['/my-clubs'] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({
    user: {
      id: VIEWER_ID, telegramId: 1, telegramUsername: 'v', firstName: 'V', lastName: null,
      avatarUrl: null, city: null, country: null, bio: null,
    },
    isAuthenticated: true,
    isLoading: false,
  } as never);
});

describe('MyClubsPage — раннее продление подписки (§7)', () => {
  it('подписка в окне T-3: секция «Подписка истекает» с кнопкой «Продлить подписку»; тап открывает шит оплаты', async () => {
    mockEndpoints(membership({ subscriptionExpiresAt: inDays(2) }));
    const { user } = renderPage();

    expect(await screen.findByText(/Подписка истекает/)).toBeInTheDocument();
    const renewButton = await screen.findByRole('button', { name: /Продлить подписку/ });

    await user.click(renewButton);
    // Открылся DuesPaymentSheet (СБП-реквизиты клуба из clubDetails).
    expect(await screen.findByRole('button', { name: /Подтвердить оплату/ })).toBeInTheDocument();
  });

  it('после claim строка показывает «Оплата на проверке» без кнопки', async () => {
    mockEndpoints(membership({ subscriptionExpiresAt: inDays(1), duesClaimedAt: inDays(0), duesClaimMethod: 'cash' }));
    renderPage();

    expect(await screen.findByText(/Подписка истекает/)).toBeInTheDocument();
    expect(await screen.findByText('Оплата на проверке')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Продлить подписку/ })).not.toBeInTheDocument();
  });

  it('вне окна (истекает через 10 дней) секции нет', async () => {
    mockEndpoints(membership({ subscriptionExpiresAt: inDays(10) }));
    renderPage();

    // Дожидаемся, пока клуб отрисуется в «Где я состою», и убеждаемся, что секции продления нет.
    expect(await screen.findByText(/Где я состою/)).toBeInTheDocument();
    expect(screen.queryByText(/Подписка истекает/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Продлить подписку/ })).not.toBeInTheDocument();
  });
});
