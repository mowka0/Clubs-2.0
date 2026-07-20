import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ApplicationDto } from '../../api/membership';
import type { MembershipDto, UserClubReputationDto } from '../../types/api';

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
const BANNER_TITLE = 'Ты сейчас не состоишь ни в одном клубе';

function membership(over: Partial<MembershipDto> = {}): MembershipDto {
  return {
    id: 'm-1',
    userId: VIEWER_ID,
    clubId: CLUB_ID,
    status: 'active',
    role: 'member',
    joinedAt: '2026-06-01T10:00:00Z',
    subscriptionExpiresAt: null,
    duesClaimedAt: null,
    duesClaimMethod: null,
    ...over,
  } as MembershipDto;
}

function pendingApplication(): ApplicationDto {
  return {
    id: 'app-1',
    userId: VIEWER_ID,
    clubId: CLUB_ID,
    status: 'pending',
    answerText: null,
    rejectedReason: null,
    createdAt: '2026-07-10T10:00:00Z',
  };
}

function historyClub(): UserClubReputationDto {
  return {
    clubId: 'club-hist',
    clubName: 'Бывший клуб',
    clubAvatarUrl: null,
    category: 'sport',
    role: 'member',
    joinedAt: '2026-01-01T00:00:00Z',
    trust: 72,
    promiseFulfillmentPct: 100,
    totalConfirmations: 5,
    totalAttendances: 5,
    spontaneityCount: 0,
    projectedNext1: null,
    projectedNext2: null,
    meetingsToReliable: null,
    skladchinaPaid: null,
    skladchinaTotal: null,
    nearestEvent: null,
    awards: [],
  };
}

function mockEndpoints(opts: {
  clubs: MembershipDto[];
  applications: ApplicationDto[];
  historyClubs: UserClubReputationDto[];
}) {
  server.use(
    http.get('*/api/users/me/clubs', () => HttpResponse.json(opts.clubs)),
    http.get('*/api/users/me/applications', () => HttpResponse.json(opts.applications)),
    http.get('*/api/users/me/applications-pending', () => HttpResponse.json([])),
    http.get('*/api/users/me/reputation', () => HttpResponse.json({
      global: { reliableClubs: 0, trackRecordClubs: 0, score: null },
      activeClubs: [],
      historyClubs: opts.historyClubs,
    })),
    http.get('*/api/clubs/:id', ({ params }) => HttpResponse.json({
      id: params.id as string, ownerId: 'someone-else', name: 'Шахматы', description: 'd',
      category: 'board_games', accessType: 'open', city: 'Москва', district: null,
      memberLimit: 20, subscriptionPrice: 0, paymentLink: null, paymentMethodNote: null,
      avatarUrl: null, rules: null, applicationQuestion: null, inviteLink: null,
      memberCount: 5, isActive: true,
    })),
  );
}

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/my-clubs" element={<MyClubsPage />} />
    </Routes>,
    { routerEntries: ['/my-clubs'] },
  );
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

describe('MyClubsPage — баннер «не состоишь ни в одном клубе» (W3-02)', () => {
  it('только История (членств нет, заявок нет) → баннер с текстом про историю над секцией «История»', async () => {
    mockEndpoints({ clubs: [], applications: [], historyClubs: [historyClub()] });
    renderPage();

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    expect(screen.getByText(/история и репутация сохранились/)).toBeInTheDocument();
    // Секция «История» под баннером на месте.
    expect(await screen.findByText(/История/)).toBeInTheDocument();
    // Это НЕ полноэкранная сцена W3-01.
    expect(screen.queryByText('Тут появятся твои клубы')).not.toBeInTheDocument();
  });

  it('только pending-заявка (членств нет) → баннер с текстом про заявку + секция «Мои заявки»', async () => {
    mockEndpoints({ clubs: [], applications: [pendingApplication()], historyClubs: [] });
    renderPage();

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    expect(screen.getByText(/Заявка уже у организатора/)).toBeInTheDocument();
    expect(await screen.findByText(/Мои заявки/)).toBeInTheDocument();
  });

  it('есть активное членство → баннера нет', async () => {
    mockEndpoints({ clubs: [membership()], applications: [], historyClubs: [] });
    renderPage();

    // Дожидаемся отрисовки секции членств, затем проверяем отсутствие баннера.
    expect(await screen.findByText(/Где я состою/)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('есть frozen-членство (без доступа) → баннера нет', async () => {
    // AC W3-02: баннер ключуется на отсутствии ЛЮБЫХ членств, включая frozen/expired —
    // граница, которую легко сломать будущим фильтром «только активные».
    mockEndpoints({ clubs: [membership({ status: 'frozen' })], applications: [], historyClubs: [] });
    renderPage();

    expect(await screen.findByText(/Доступ закрыт — оплатите/)).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });

  it('всё пусто → баннера нет, работает пустая сцена W3-01', async () => {
    mockEndpoints({ clubs: [], applications: [], historyClubs: [] });
    renderPage();

    expect(await screen.findByText('Тут появятся твои клубы')).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });
});
