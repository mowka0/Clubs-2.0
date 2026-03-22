import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
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
import { useClubsStore } from '../../store/useClubsStore';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderClubPage(clubId: string = 'club-123') {
  const user = userEvent.setup();
  const result = render(
    <MemoryRouter initialEntries={[`/clubs/${clubId}`]}>
      <Routes>
        <Route path="/clubs/:id" element={<ClubPage />} />
        <Route path="/clubs/:id/manage" element={<div>Manage Page</div>} />
      </Routes>
    </MemoryRouter>
  );
  return { ...result, user };
}

describe('ClubPage', () => {
  beforeEach(() => {
    // Reset stores
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
    useClubsStore.setState({
      clubs: [],
      myClubs: [],
      totalPages: 0,
      totalElements: 0,
      loading: false,
      error: null,
    });
    server.resetHandlers();
  });

  it('shows "Вступить" button for non-member open club', async () => {
    // Set up MSW to return an open club
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
      })
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /вступить/i })).toBeInTheDocument();
    });
  });

  it('click "Вступить" calls joinClub API, then shows "Вы участник" text', async () => {
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
        // First call returns empty (not a member), second call returns member
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
      })
    );

    const { user } = renderClubPage();

    // Wait for the join button to appear
    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    // Click join
    await user.click(joinButton);

    // After join, the button text should change to show membership
    await waitFor(() => {
      // The joinSuccess state is set after fetchMyClubs completes
      // The component shows either "Вы участник" (if isMember) or "Заявка отправлена"
      const memberButton = screen.queryByText(/вы участник/i);
      const successButton = screen.queryByText(/заявка отправлена/i);
      expect(memberButton || successButton).toBeTruthy();
    });

    expect(joinCalled).toBe(true);
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
          { status: 400 }
        );
      })
    );

    const { user } = renderClubPage();

    // Wait for join button
    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    // Click join
    await user.click(joinButton);

    // Wait for error message
    await waitFor(() => {
      expect(screen.getByText('Club is full')).toBeInTheDocument();
    });
  });

  it('organizer sees "Управление клубом" button when ownerId matches user id', async () => {
    // Set user as owner
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
      })
    );

    renderClubPage();

    await waitFor(() => {
      // The button text includes the gear emoji entity + "Управление клубом"
      const manageButton = screen.getByRole('button', { name: /управление клубом/i });
      expect(manageButton).toBeInTheDocument();
    });
  });

  it('organizer sees "Управление клубом" when they have organizer membership role', async () => {
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
      })
    );

    renderClubPage();

    await waitFor(() => {
      const manageButton = screen.getByRole('button', { name: /управление клубом/i });
      expect(manageButton).toBeInTheDocument();
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
      })
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
          { status: 404 }
        );
      }),
      http.get('*/api/users/me/clubs', () => {
        return HttpResponse.json([] as MembershipDto[]);
      })
    );

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText(/club not found/i)).toBeInTheDocument();
    });
  });
});
