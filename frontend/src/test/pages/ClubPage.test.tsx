import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ClubDetailDto, MembershipDto } from '../../types/api';

// Мок Telegram SDK
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

// Мок Telegram UI
vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));

// Мок нашего модуля telegram/sdk
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

// Импорт после моков
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

// Хелперы для сценариев участника/организатора — по умолчанию эндпоинты данных табов отдают пусто.
function mockEmptyTabData(clubId: string = 'club-123') {
  server.use(
    http.get(`*/api/clubs/${clubId}/activities`, () => HttpResponse.json({
      upcoming: [],
      past: [],
    })),
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
      role: 'member',
      trust: 100,
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
        country: null,
        bio: null,
        onboardedAt: '2026-01-01T00:00:00Z',
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

  it('visitor sees placeholder "Активности клуба доступны участникам" and no tabs', async () => {
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
      expect(screen.getByText(/активности клуба доступны участникам/i)).toBeInTheDocument();
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

  it('closed paid club with approved application (no membership yet): shows organizer-will-open note', async () => {
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

    // De-Stars: Stars-инвойса больше нет. Одобрение сразу впускает участника (frozen); fallback-CTA для
    // legacy-строки approved-без-membership лишь сообщает, что доступ откроет организатор.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /организатор откроет доступ/i })).toBeInTheDocument();
    });
    expect(screen.queryByText(/stars/i)).not.toBeInTheDocument();
  });

  it('paid club: joining lands frozen — shows pending-access note, not full member', async () => {
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
        // De-Stars: join всегда возвращает 201 + MembershipDto; в платном клубе membership попадает в `frozen`.
        return HttpResponse.json(
          {
            id: 'm-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'frozen',
            role: 'member',
            joinedAt: '2025-01-01T00:00:00Z',
            subscriptionExpiresAt: null,
          } as MembershipDto,
          { status: 201 },
        );
      }),
    );

    const { user } = renderClubPage();

    const joinButton = await waitFor(() => {
      return screen.getByRole('button', { name: /вступить/i });
    });

    await user.click(joinButton);

    await waitFor(() => {
      expect(screen.getByText(/вы вступили в клуб/i)).toBeInTheDocument();
    });

    expect(screen.queryByText(/вы участник/i)).not.toBeInTheDocument();
  });

  it('expired member: shows «Подписка истекла» with «Оплатить взнос», no guest CTA and no tabs', async () => {
    // Оживление expired (PO 2026-07-06): должник по продлению видит зеркальный frozen-вид,
    // но текст говорит о продлении подписки, а не о вступлении. Claim-флоу тот же.
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
        return HttpResponse.json([
          {
            id: 'm-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'expired',
            role: 'member',
            joinedAt: '2025-01-01T00:00:00Z',
            subscriptionExpiresAt: '2025-06-01T00:00:00Z',
          },
        ] as MembershipDto[]);
      }),
    );
    mockEmptyTabData();

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText(/подписка истекла/i)).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /оплатить взнос/i })).toBeInTheDocument();
    // Не гость (нет «Вступить») и не полноправный участник (нет табов/бейджа).
    expect(screen.queryByRole('button', { name: /^вступить$/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/вы участник/i)).not.toBeInTheDocument();
  });

  it('expired member with a pending claim: shows «Оплата на проверке» instead of the pay CTA', async () => {
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
        return HttpResponse.json([
          {
            id: 'm-1',
            userId: 'user-1',
            clubId: 'club-123',
            status: 'expired',
            role: 'member',
            joinedAt: '2025-01-01T00:00:00Z',
            subscriptionExpiresAt: '2025-06-01T00:00:00Z',
            duesClaimedAt: '2025-06-02T00:00:00Z',
            duesClaimMethod: 'sbp',
          },
        ] as MembershipDto[]);
      }),
    );
    mockEmptyTabData();

    renderClubPage();

    await waitFor(() => {
      expect(screen.getByText(/оплата на проверке/i)).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /оплатить взнос/i })).not.toBeInTheDocument();
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

  it('member sees role-aware tabs (activities / members) without manage tab and without CTA', async () => {
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
      expect(screen.getByRole('button', { name: /^активности$/i })).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /^участники$/i })).toBeInTheDocument();
    // Таб «Мой профиль» убран — per-club репутация теперь живёт в глобальном Профиле.
    expect(screen.queryByRole('button', { name: /^мой профиль$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^управление$/i })).not.toBeInTheDocument();
    expect(screen.getByText(/вы участник/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^вступить$/i })).not.toBeInTheDocument();
  });

  it('members tab (non-organizer viewer): shows real score, HIDES «Новичок» for hidden trust, keeps organizer framing', async () => {
    // Смотрящий user-1 — обычный участник (владелец other-owner). Асимметрия #94: чужой скрытый скор
    // приходит как trust=null и неотличим от новичка → ложный «Новичок» показывать нельзя (пишем ничего).
    server.use(
      http.get('*/api/clubs/:id', () =>
        HttpResponse.json({ ...mockClubDetail, ownerId: 'other-owner' } as ClubDetailDto)),
      http.get('*/api/users/me/clubs', () =>
        HttpResponse.json([
          { id: 'mem-1', userId: 'user-1', clubId: 'club-123', status: 'active', role: 'member', joinedAt: '2025-01-01T00:00:00Z', subscriptionExpiresAt: null },
        ] as MembershipDto[])),
      http.get('*/api/clubs/club-123/activities', () => HttpResponse.json({ upcoming: [], past: [] })),
      http.get('*/api/clubs/club-123/events', () =>
        HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 100 })),
      http.get('*/api/clubs/club-123/members', () =>
        HttpResponse.json([
          { userId: 'u-vet', firstName: 'Vet', lastName: null, avatarUrl: null, role: 'member', joinedAt: '2025-01-01T00:00:00Z', trust: 90, promiseFulfillmentPct: 95 },
          { userId: 'u-new', firstName: 'Newbie', lastName: null, avatarUrl: null, role: 'member', joinedAt: '2025-02-01T00:00:00Z', trust: null, promiseFulfillmentPct: null },
          { userId: 'u-org', firstName: 'Org', lastName: null, avatarUrl: null, role: 'organizer', joinedAt: '2025-01-01T00:00:00Z', trust: null, promiseFulfillmentPct: null },
        ])),
    );

    const { user } = renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^участники$/i })).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: /^участники$/i }));

    // Ветеран: реальное число + тир (пришёл скор — показываем).
    await waitFor(() => {
      expect(screen.getByText('90')).toBeInTheDocument();
    });
    // Скрытый скор участника: НЕ пишем «Новичок» чужому зрителю (иначе врём тому, у кого история есть).
    expect(screen.queryByText('Новичок')).not.toBeInTheDocument();
    // Ролевая подача организатора — не скор, видна всем.
    expect(screen.getByText(/репутация за организаторские качества/i)).toBeInTheDocument();
  });

  it('members tab (organizer viewer): shows «Новичок» for a genuine no-track-record member', async () => {
    // Владелец видит скоры честно: у него trust=null означает именно «нет истории» → «Новичок» уместен.
    useAuthStore.setState({
      user: { id: 'owner-1', telegramId: 1, telegramUsername: null, firstName: 'O', lastName: null, avatarUrl: null, city: null, country: null, bio: null, onboardedAt: '2026-01-01T00:00:00Z' },
      isAuthenticated: true, isLoading: false, error: null,
    });
    server.use(
      http.get('*/api/clubs/:id', () =>
        HttpResponse.json({ ...mockClubDetail, ownerId: 'owner-1' } as ClubDetailDto)),
      http.get('*/api/users/me/clubs', () => HttpResponse.json([] as MembershipDto[])),
      http.get('*/api/clubs/club-123/activities', () => HttpResponse.json({ upcoming: [], past: [] })),
      http.get('*/api/clubs/club-123/events', () =>
        HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, page: 0, size: 100 })),
      http.get('*/api/clubs/club-123/members', () =>
        HttpResponse.json([
          { userId: 'u-new', firstName: 'Newbie', lastName: null, avatarUrl: null, role: 'member', joinedAt: '2025-02-01T00:00:00Z', trust: null, promiseFulfillmentPct: null },
        ])),
    );

    const { user } = renderClubPage();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^участники$/i })).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: /^участники$/i }));

    expect(await screen.findByText('Новичок')).toBeInTheDocument();
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
        country: null,
        bio: null,
        onboardedAt: '2026-01-01T00:00:00Z',
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
        country: null,
        bio: null,
        onboardedAt: '2026-01-01T00:00:00Z',
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
    // Редизайн: цена — часть единой мета-строки hero-eyebrow
    // («доступ · город · N/limit · цена»), поэтому матчим её как подстроку.
    expect(screen.getByText(/200 ₽ \/ мес/)).toBeInTheDocument();
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
