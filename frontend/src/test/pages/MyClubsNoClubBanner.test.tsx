import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import { withStage1Profile } from '../mocks/productProfile';
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
// Взносы, заявки и прочее спрятанное на этапе 1 проверяются под профилем этапа 2 (mocks/productProfile).
vi.mock('../../config/productProfile', () => import('../mocks/productProfile'));
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
      <Route path="/connect-chat" element={<div>Экран подключения чата</div>} />
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
    // Вместо «Открыть Поиск» (каталог убран из навигации) — создание клуба из чата.
    expect(screen.queryByRole('button', { name: 'Открыть Поиск' })).not.toBeInTheDocument();
    // Секция «История» под баннером на месте.
    expect(await screen.findByText(/История/)).toBeInTheDocument();
    // Это НЕ полноэкранная сцена W3-01.
    expect(screen.queryByText('Прокачай чат до настоящего клуба')).not.toBeInTheDocument();
    // Отдельный адрес, а не «/»: на «/» есть док, и нативный «назад» там спрятан.
    await userEvent.click(screen.getByRole('button', { name: 'Создать клуб из чата' }));
    expect(await screen.findByText('Экран подключения чата')).toBeInTheDocument();
  });

  it('только pending-заявка (членств нет) → баннер с текстом про заявку + секция «Мои заявки»', async () => {
    mockEndpoints({ clubs: [], applications: [pendingApplication()], historyClubs: [] });
    renderPage();

    expect(await screen.findByText(BANNER_TITLE)).toBeInTheDocument();
    expect(screen.getByText(/Заявка уже у организатора/)).toBeInTheDocument();
    expect(screen.queryByText(/Поиск/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать клуб из чата' })).toBeInTheDocument();
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

  it('всё пусто → баннера нет, работает сцена «подключите чат»', async () => {
    mockEndpoints({ clubs: [], applications: [], historyClubs: [] });
    renderPage();

    expect(await screen.findByText('Прокачай чат до настоящего клуба')).toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
  });
});

describe('MyClubsPage — этап 1: без заявок, взносов, категории и создания с нуля', () => {
  withStage1Profile();

  it('одна pending-заявка не считается: пустой экран «подключи чат», без «Мои заявки»', async () => {
    mockEndpoints({ clubs: [], applications: [pendingApplication()], historyClubs: [] });
    renderPage();

    expect(await screen.findByText('Прокачай чат до настоящего клуба')).toBeInTheDocument();
    expect(screen.queryByText(/Мои заявки/)).not.toBeInTheDocument();
    expect(screen.queryByText(BANNER_TITLE)).not.toBeInTheDocument();
    expect(screen.queryByText(/заявк/)).not.toBeInTheDocument();
  });

  it('frozen-членство живёт в «Где я состою», без «Доступ закрыт — оплатите»; мета без категории и лимита', async () => {
    mockEndpoints({ clubs: [membership({ status: 'frozen' })], applications: [], historyClubs: [historyClub()] });
    renderPage();

    expect(await screen.findByText(/Где я состою/)).toBeInTheDocument();
    expect(screen.queryByText(/Доступ закрыт — оплатите/)).not.toBeInTheDocument();
    expect(await screen.findByText('участник · 5 участников')).toBeInTheDocument();
    expect(screen.queryByText(/Настолки/)).not.toBeInTheDocument();
    expect(screen.queryByText(/5 \/ 20/)).not.toBeInTheDocument();
    // «История» тоже без категории.
    expect(screen.getByText('вы покинули')).toBeInTheDocument();
  });

  it('«+ Клуб» ведёт к подключению чата, развилки «Создать с нуля» нет', async () => {
    mockEndpoints({ clubs: [membership()], applications: [], historyClubs: [] });
    // Ссылка на бота ещё не приехала — экран подключения дождётся её сам.
    server.use(http.get('*/api/chat-link/new-club-url', () => HttpResponse.json({ message: 'down' }, { status: 500 })));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Создать клуб' }));

    expect(await screen.findByText('Экран подключения чата')).toBeInTheDocument();
    expect(screen.queryByText('Создать с нуля')).not.toBeInTheDocument();
  });
});
