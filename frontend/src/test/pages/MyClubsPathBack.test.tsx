import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { UserClubReputationDto } from '../../types/api';

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

/** Активный клуб вызывающего с просадкой Trust и полным «путём назад». */
function repClub(overrides: Partial<UserClubReputationDto> = {}): UserClubReputationDto {
  return {
    clubId: CLUB_ID,
    clubName: 'Партия',
    clubAvatarUrl: null,
    category: 'board_games',
    role: 'member',
    joinedAt: '2026-06-01T10:00:00Z',
    trust: 60,
    promiseFulfillmentPct: 71,
    totalConfirmations: 9,
    totalAttendances: 7,
    spontaneityCount: 0,
    projectedNext1: 66,
    projectedNext2: 70,
    meetingsToReliable: 2,
    skladchinaPaid: 3,
    skladchinaTotal: 3,
    nearestEvent: { id: 'evt-1', title: 'Покерный вечер', eventDatetime: '2026-07-09T19:00:00Z', goingCount: 5 },
    awards: [{ id: 'aw-1', emoji: '🏆', label: 'Душа компании' }],
    ...overrides,
  };
}

function mockEndpoints(rep: UserClubReputationDto) {
  server.use(
    http.get('*/api/users/me/clubs', () => HttpResponse.json([
      {
        id: 'm-1', userId: VIEWER_ID, clubId: CLUB_ID, status: 'active', role: 'member',
        joinedAt: '2026-06-01T10:00:00Z', subscriptionExpiresAt: null, duesClaimedAt: null, duesClaimMethod: null,
      },
    ])),
    http.get('*/api/users/me/applications', () => HttpResponse.json([])),
    http.get('*/api/users/me/applications-pending', () => HttpResponse.json([])),
    http.get('*/api/users/me/reputation', () => HttpResponse.json({
      global: { reliableClubs: 0, trackRecordClubs: 1, score: 60 },
      activeClubs: [rep],
      historyClubs: [],
    })),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID, ownerId: 'someone-else', name: 'Партия', description: 'd', category: 'board_games',
      accessType: 'open', city: 'Москва', district: null, memberLimit: 20, subscriptionPrice: 0,
      avatarUrl: null, rules: null, applicationQuestion: null, inviteLink: null, memberCount: 14, isActive: true,
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

describe('MyClubsPage — раскрывающаяся карточка клуба с «путём наверх»', () => {
  it('свёрнутая карточка показывает надёжность справа; тап раскрывает «Путь наверх» с траекторией', async () => {
    mockEndpoints(repClub());
    const { user } = renderPage();

    // Свёрнуто: имя клуба + число надёжности; деталей ещё нет.
    const head = await screen.findByRole('button', { name: /Партия/ });
    expect(head).toHaveTextContent('60');
    expect(head).toHaveTextContent('надёжность');
    // Тело теперь всегда в DOM (плавная анимация) — свёрнутость проверяем по aria-expanded.
    expect(head).toHaveAttribute('aria-expanded', 'false');

    await user.click(head);
    expect(head).toHaveAttribute('aria-expanded', 'true');

    // Раскрыто: метрики, траектория 60 → 66 → 70, подпись зоны, встреча, «Открыть клуб».
    expect(screen.getByText('Путь наверх')).toBeInTheDocument();
    expect(screen.getByText(/обещания 71%.*сборы 3\/3/)).toBeInTheDocument();
    expect(screen.getByText('66')).toBeInTheDocument();
    expect(screen.getByText('70')).toBeInTheDocument();
    expect(screen.getByText('надёжная зона')).toBeInTheDocument();
    expect(screen.getByText(/Две ближайшие встречи вернут вас/)).toBeInTheDocument();
    expect(screen.getByText(/Покерный вечер/)).toBeInTheDocument();
    expect(screen.getByText(/Душа компании/)).toBeInTheDocument();   // чип клубной награды
    expect(screen.getByRole('button', { name: /Открыть клуб/ })).toBeInTheDocument();

    // Повторный тап сворачивает.
    await user.click(head);
    expect(head).toHaveAttribute('aria-expanded', 'false');
  });

  it('здоровый Trust (82, projected null): раскрытый вид БЕЗ «пути наверх», метрики на месте', async () => {
    mockEndpoints(repClub({
      trust: 82, projectedNext1: null, projectedNext2: null, meetingsToReliable: null,
      promiseFulfillmentPct: 90, totalConfirmations: 10, totalAttendances: 9,
    }));
    const { user } = renderPage();

    const head = await screen.findByRole('button', { name: /Партия/ });
    await user.click(head);

    expect(screen.getByText(/обещания 90%/)).toBeInTheDocument();
    expect(screen.queryByText('Путь наверх')).not.toBeInTheDocument();
  });

  it('новичок (trust null): свёрнуто «Новичок», раскрыто — пояснение без метрик', async () => {
    mockEndpoints(repClub({
      trust: null, promiseFulfillmentPct: null, totalConfirmations: null, totalAttendances: null,
      spontaneityCount: null, projectedNext1: null, projectedNext2: null, meetingsToReliable: null,
      skladchinaPaid: null, skladchinaTotal: null, nearestEvent: null, awards: [],
    }));
    const { user } = renderPage();

    const head = await screen.findByRole('button', { name: /Партия/ });
    expect(head).toHaveTextContent('Новичок');

    await user.click(head);
    expect(screen.getByText(/Статистика накопится после трёх посещений/)).toBeInTheDocument();
    expect(screen.queryByText('Путь наверх')).not.toBeInTheDocument();
  });
});
