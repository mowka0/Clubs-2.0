import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ClubDetailDto, MembershipDto } from '../../types/api';

// Mock Telegram SDK
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

// Mock Telegram UI
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Mock the telegram sdk module
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

// Import after mocks
import { ClubPage } from '../../pages/ClubPage';
import { useAuthStore } from '../../store/useAuthStore';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderClubPage(clubId: string = 'club-123') {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id" element={<ClubPage />} />
      <Route path="/clubs/:id/manage" element={<div>Manage Page</div>} />
    </Routes>,
    { routerEntries: [`/clubs/${clubId}`] },
  );
  return { ...result, user };
}

// Helpers for member/organizer scenarios — keep tab data endpoints empty by default.
function mockEmptyTabData(clubId: string = 'club-123') {
  server.use(
    http.get(`*/api/clubs/${clubId}/events`, () => HttpResponse.json({
      content: [],
      totalElements: 0,
      totalPages: 0,
      page: 0,
      size: 100,
    })),
    http.get(`*/api/clubs/${clubId}/members`, () => HttpResponse.json([])),
    http.get(`*/api/clubs/${clubId}/members/:userId`, () => HttpResponse.json({
      userId: 'user-1',
      clubId,
      firstName: 'Test',
      username: 'testuser',
      avatarUrl: null,
      reliabilityIndex: 100,
      promiseFulfillmentPct: 100,
      totalConfirmations: 0,
      totalAttendances: 0,
    })),
  );
}

describe('ClubPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: {
        id: 'user-1',
        telegramId: 12345,
        telegramUsername: 'testuser',
        firstName: 'Test',
        lastName: 'User',
        avatarUrl: null,
        city: null,
      },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });
    server.resetHandlers();
  });

  it('shows "Вступить" button for non-member open club', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'open',
          ownerId: 'other-owner',
        });
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /вступить/i })).toBeInTheDocument();
    });
  });

  it('visitor sees placeholder "События доступны участникам клуба" and no tabs', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'open',
          ownerId: 'other-owner',
        });
      }),
      http.get('*/api/users/me/clubs', () => HttpResponse.json([] as MembershipDto[])),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText(/события доступны участникам клуба/i)).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /^участники$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^мой профиль$/i })).not.toBeInTheDocument();
  });

  it('click "Вступить" calls joinClub API, then shows "Вы участник" badge', async () => {
    let joinCalled = false;

    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'open',
          ownerId: 'other-owner',
        });
      }),
      http.get('*/api/users/me/clubs', () => {
        if (joinCalled) {
          return HttpResponse.json([
            {
              id: 'mem-1',
              userId: 'user-1',
              clubId: 'club-123',
              status: 'active',
              role: 'member',
              joinedAt: '2025-01-01T00:00:00Z',
              subscriptionExpiresAt: null,
            },
          ] as MembershipDto[]);
        }
        return HttpResponse.json([] as MembershipDto[]);
      }),
      http.post('*/api/clubs/:id/join', () => {
        joinCalled = true;
        return HttpResponse.json({
          id: 'mem-1',
          userId: 'user-1',
          clubId: 'club-123',
          status: 'active',
          role: 'member',
          joinedAt: '2025-01-01T00:00:00Z',
          subscriptionExpiresAt: null,
        } as MembershipDto);
      }),
    );
    mockEmptyTabData();

    const { user } = renderClubPage();

    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    await user.click(joinButton);

    await waitFor(() => {
      expect(screen.getByText(/вы участник/i)).toBeInTheDocument();
    });

    expect(joinCalled).toBe(true);
  });

  it('closed club with pending application: button is disabled and shows "Заявка на рассмотрении"', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'closed',
          ownerId: 'other-owner',
          subscriptionPrice: 500,
        });
      }),
      http.get('*/api/users/me/clubs', () => HttpResponse.json([] as MembershipDto[])),
      http.get('*/api/users/me/applications', () => {
        return HttpResponse.json([
          {
            id: 'app-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'pending',
            answerText: null,
            createdAt: '2025-01-01T00:00:00Z',
          },
        ]);
      }),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /заявка на рассмотрении/i })).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /^хочу вступить$/i })).not.toBeInTheDocument();
  });

  it('closed club with approved application: shows "Ожидаем оплату" with club price', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'closed',
          ownerId: 'other-owner',
          subscriptionPrice: 750,
        });
      }),
      http.get('*/api/users/me/clubs', () => HttpResponse.json([] as MembershipDto[])),
      http.get('*/api/users/me/applications', () => {
        return HttpResponse.json([
          {
            id: 'app-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'approved',
            answerText: null,
            createdAt: '2025-01-01T00:00:00Z',
          },
        ]);
      }),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /ожидаем оплату/i })).toBeInTheDocument();
      expect(screen.getByText(/заявка одобрена/i)).toBeInTheDocument();
    });
  });

  it('paid club: pending_payment response shows "Ожидаем оплату" and does not mark user as member', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'open',
          ownerId: 'other-owner',
          subscriptionPrice: 500,
        });
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
      http.post('*/api/clubs/:id/join', () => {
        return HttpResponse.json(
          {
            status: 'pending_payment',
            clubId: 'club-123',
            priceStars: 500,
            message: 'Оплатите подписку через бота. Счёт отправлен в Telegram.',
          },
          { status: 202 },
        );
      }),
    );

    const { user } = renderClubPage();

    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    await user.click(joinButton);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /ожидаем оплату/i })).toBeInTheDocument();
      expect(screen.getByText(/счёт отправлен в telegram/i)).toBeInTheDocument();
    });

    expect(screen.queryByText(/вы участник/i)).not.toBeInTheDocument();
  });

  it('shows error message when join API fails', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          accessType: 'open',
          ownerId: 'other-owner',
        });
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
      http.post('*/api/clubs/:id/join', () => {
        return HttpResponse.json(
          { message: 'Club is full' },
          { status: 400 },
        );
      }),
    );

    const { user } = renderClubPage();

    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    await user.click(joinButton);

    await waitFor(() => {
      expect(screen.getByText('Club is full')).toBeInTheDocument();
    });
  });

  it('member sees role-aware tabs (events / members / profile) without manage tab and without CTA', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          ownerId: 'other-owner',
        } as ClubDetailDto);
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([
          {
            id: 'mem-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'active',
            role: 'member',
            joinedAt: '2025-01-01T00:00:00Z',
            subscriptionExpiresAt: null,
          },
        ] as MembershipDto[]);
      }),
    );
    mockEmptyTabData();

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^события$/i })).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /^участники$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^мой профиль$/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^управление$/i })).not.toBeInTheDocument();
    expect(screen.getByText(/вы участник/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^вступить$/i })).not.toBeInTheDocument();
  });

  it('organizer sees extra "Управление" tab when ownerId matches user id', async () => {
    useAuthStore.setState({
      user: {
        id: 'owner-456',
        telegramId: 12345,
        telegramUsername: 'owner',
        firstName: 'Owner',
        lastName: 'User',
        avatarUrl: null,
        city: null,
      },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          ownerId: 'owner-456',
        } as ClubDetailDto);
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
    );
    mockEmptyTabData();

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^управление$/i })).toBeInTheDocument();
    });
    expect(screen.getByText(/вы организатор/i)).toBeInTheDocument();
  });

  it('organizer sees "Управление" tab when they have organizer membership role', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          ownerId: 'someone-else',
        } as ClubDetailDto);
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([
          {
            id: 'mem-org',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'active',
            role: 'organizer',
            joinedAt: '2025-01-01T00:00:00Z',
            subscriptionExpiresAt: null,
          },
        ] as MembershipDto[]);
      }),
    );
    mockEmptyTabData();

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^управление$/i })).toBeInTheDocument();
    });
  });

  it('organizer tap on "Управление" tab navigates to /clubs/:id/manage', async () => {
    useAuthStore.setState({
      user: {
        id: 'owner-456',
        telegramId: 12345,
        telegramUsername: 'owner',
        firstName: 'Owner',
        lastName: 'User',
        avatarUrl: null,
        city: null,
      },
      isAuthenticated: true,
      isLoading: false,
      error: null,
    });

    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          ownerId: 'owner-456',
        } as ClubDetailDto);
      }),
      http.get('*/api/users/me/clubs', () => HttpResponse.json([] as MembershipDto[])),
    );
    mockEmptyTabData();

    const { user } = renderClubPage();

    const manageTab = await waitFor(() => {
      return screen.getByRole('button', { name: /^управление$/i });
    });

    await user.click(manageTab);

    await waitFor(() => {
      expect(screen.getByText('Manage Page')).toBeInTheDocument();
    });
  });

  it('shows club info correctly after loading', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json({
          ...mockClubDetail,
          name: 'Книжный клуб',
          city: 'Москва',
          description: 'Клуб для любителей чтения',
          subscriptionPrice: 200,
          ownerId: 'other-owner',
        } as ClubDetailDto);
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText('Книжный клуб')).toBeInTheDocument();
    });

    expect(screen.getByText('Клуб для любителей чтения')).toBeInTheDocument();
    expect(screen.getByText('200 Stars / мес')).toBeInTheDocument();
  });

  it('displays error placeholder when API returns an error', async () => {
    server.use(
      http.get('*/api/clubs/:id', () => {
        return HttpResponse.json(
          { message: 'Club not found' },
          { status: 404 },
        );
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      }),
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText(/club not found/i)).toBeInTheDocument();
    });
  });
});
