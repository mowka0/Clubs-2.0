import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { mockClubDetail } from '../mocks/handlers';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ClubStatsDto } from '../../types/api';

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
import { OrganizerClubManage } from '../../pages/OrganizerClubManage';
import { useAuthStore } from '../../store/useAuthStore';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CLUB_ID = 'club-123';
// Владелец клуба из mockClubDetail (ownerId: 'owner-456').
const OWNER_ID = 'owner-456';
// Любой другой авторизованный пользователь — на этой странице трактуется как со-организатор
// (страница открывается только менеджерам, гейт входа — таб «Управление» на ClubPage).
const CO_ORG_ID = 'user-1';

const EMPTY_STATS: ClubStatsDto = {
  clubType: 'free', retentionPercent: null, retentionTrend: null, churnedThisPeriod: 0,
  rejoinedThisPeriod: 0, engagementPercent: 50, engagementTrend: null, skladchinaPaidPercent: null,
  skladchinaPaidTrend: null, pendingApplications: null, stalePendingApplications: null,
  attendanceDisputes: 0, totalMeetings: 3, autoRejectedApplications: null, cancelledMeetings: 0,
};

function setViewer(userId: string) {
  useAuthStore.setState({
    user: {
      id: userId, telegramId: 1, telegramUsername: 'viewer', firstName: 'Viewer',
      lastName: null, avatarUrl: null, city: null, country: null, bio: null,
      onboardedAt: '2026-01-01T00:00:00Z',
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
  });
}

function mockPaidClub() {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      ...mockClubDetail,
      subscriptionPrice: 500,
      paymentLink: 'https://sbp.example/pay',
    })),
  );
}

function renderManage(entry: string = `/clubs/${CLUB_ID}/manage`) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/manage" element={<OrganizerClubManage />} />
    </Routes>,
    { routerEntries: [entry] },
  );
  return { ...result, user };
}

describe('OrganizerClubManage — набор табов и владельческие секции (co-organizers)', () => {
  beforeEach(() => {
    server.resetHandlers();
    server.use(http.get(`*/api/clubs/${CLUB_ID}/stats`, () => HttpResponse.json(EMPTY_STATS)));
  });

  it('владелец видит все 4 таба (включая «Чат») и «Удалить клуб» в настройках', async () => {
    setViewer(OWNER_ID);
    mockPaidClub();
    const { user } = renderManage();

    expect(await screen.findByRole('tab', { name: 'Статистика' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Финансы' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Чат' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Настройки' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Настройки' }));
    expect(await screen.findByRole('button', { name: 'Удалить клуб' })).toBeInTheDocument();
    expect(screen.getByText(/Реквизиты для взноса/)).toBeInTheDocument();
  });

  it('со-организатору таб «Чат» скрыт (У-10), в настройках нет «Удалить клуб» и СБП-реквизитов', async () => {
    setViewer(CO_ORG_ID);
    mockPaidClub();
    const { user } = renderManage();

    expect(await screen.findByRole('tab', { name: 'Статистика' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Финансы' })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Чат' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Настройки' })).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Настройки' }));
    // Настройки со-оргу доступны (поле «Название» на месте) — но без владельческих секций.
    expect(await screen.findByText('Название')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Удалить клуб' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Реквизиты для взноса/)).not.toBeInTheDocument();
    expect(screen.queryByText('Опасная зона')).not.toBeInTheDocument();
  });

  it('deep-link ?tab=chat у со-орга откатывается на «Статистику»', async () => {
    setViewer(CO_ORG_ID);
    mockPaidClub();
    renderManage(`/clubs/${CLUB_ID}/manage?tab=chat`);

    const statsTab = await screen.findByRole('tab', { name: 'Статистика' });
    expect(statsTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByRole('tab', { name: 'Чат' })).not.toBeInTheDocument();
  });
});
