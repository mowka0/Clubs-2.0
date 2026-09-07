import { describe, it, expect, vi, beforeAll, beforeEach, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';

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

import { CreateSplitBillPage } from '../../pages/CreateSplitBillPage';
import { useAuthStore } from '../../store/useAuthStore';

const CLUB_ID = 'club-1';
const EVENT_ID = 'event-1';
const ORGANIZER_ID = 'u-org';

function responder(userId: string, firstName: string) {
  return {
    userId, firstName, lastName: null, avatarUrl: null,
    status: 'confirmed', seat: null, attendance: 'attended', disputeNote: null,
  };
}

// Организатор + трое пришедших: при «исключить себя» к оплате остаются трое.
function mockAttendance() {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () =>
      HttpResponse.json({ id: EVENT_ID, clubId: CLUB_ID, title: 'Ужин в «Веранде»' })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () =>
      HttpResponse.json([
        responder(ORGANIZER_ID, 'Орг'),
        responder('u-a', 'Анна'),
        responder('u-b', 'Борис'),
        responder('u-c', 'Вика'),
      ])),
  );
}

function renderPage(search = `?eventId=${EVENT_ID}`) {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/clubs/:id/split-bill/new" element={<CreateSplitBillPage />} />
    </Routes>,
    { routerEntries: [`/clubs/${CLUB_ID}/split-bill/new${search}`] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
beforeEach(() => {
  useAuthStore.setState({
    user: {
      id: ORGANIZER_ID, telegramId: 1, telegramUsername: 'org', firstName: 'Орг', lastName: null,
      avatarUrl: null, city: null, country: null, cityId: null, bio: null, onboardingTours: [],
    },
    isAuthenticated: true,
    isLoading: false,
    error: null,
  });
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('CreateSplitBillPage — «я уже внёс» и описание', () => {
  it('вычитает взнос организатора из чека и шлёт selfPaidKopecks с описанием', async () => {
    mockAttendance();
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ id: 'sk-1' }, { status: 201 });
      }),
    );
    const { user } = renderPage();

    await user.click(await screen.findByRole('checkbox'));           // исключить себя
    await user.type(screen.getByPlaceholderText('Например, 1500'), '1500');
    await user.type(screen.getByPlaceholderText('Например, 4000'), '4000');
    await user.type(screen.getByPlaceholderText(/tinkoff/), 'https://pay.example/x');
    await user.type(screen.getByPlaceholderText(/еда и напитки/i), 'Ужин на компанию');

    // 4000 − 1500 = 2500 на троих ≈ 833 ₽.
    expect(screen.getByText(/Ваши 1 500 ₽ зачтены · остальные ≈ по 833 ₽ \(3 чел\.\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.selfPaidKopecks).toBe(150000);
    expect(body!.excludeSelf).toBe(true);
    expect(body!.totalGoalKopecks).toBe(400000);
    expect(body!.description).toBe('Ужин на компанию');
  });

  it('не отправляет запрос, если взнос не меньше суммы чека', async () => {
    mockAttendance();
    let called = false;
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/skladchinas`, () => {
        called = true;
        return HttpResponse.json({ id: 'sk-2' }, { status: 201 });
      }),
    );
    const { user } = renderPage();

    await user.click(await screen.findByRole('checkbox'));
    await user.type(screen.getByPlaceholderText('Например, 1500'), '4000');
    await user.type(screen.getByPlaceholderText('Например, 4000'), '4000');
    await user.type(screen.getByPlaceholderText(/tinkoff/), 'https://pay.example/x');
    await user.click(screen.getByRole('button', { name: 'Создать сбор' }));

    expect(
      await screen.findByText('Ваша сумма должна быть меньше суммы чека — иначе собирать нечего'),
    ).toBeInTheDocument();
    expect(called).toBe(false);
  });

  it('снятие галки «исключить себя» убирает поле взноса и возвращает делёж всего чека', async () => {
    mockAttendance();
    const { user } = renderPage();

    const checkbox = await screen.findByRole('checkbox');
    await user.click(checkbox);
    await user.type(screen.getByPlaceholderText('Например, 1500'), '1500');
    await user.type(screen.getByPlaceholderText('Например, 4000'), '4000');

    await user.click(checkbox);
    expect(screen.queryByPlaceholderText('Например, 1500')).not.toBeInTheDocument();
    // Взнос сброшен: 4000 делятся на всех четверых.
    expect(screen.getByText('≈ по 1 000 ₽ с каждого (4 чел.)')).toBeInTheDocument();
  });
});

describe('CreateSplitBillPage — выбор встречи', () => {
  it('показывает встречи из списка пригодных с числом пришедших и открывает форму по тапу', async () => {
    mockAttendance();
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/skladchinas/splittable-events`, () =>
        HttpResponse.json([
          { eventId: EVENT_ID, title: 'Ужин в «Веранде»', eventDatetime: '2026-09-01T18:00:00Z', attendedCount: 4 },
        ])),
    );
    const { user } = renderPage('');

    const row = await screen.findByText('Ужин в «Веранде»');
    expect(screen.getByText(/пришли 4/)).toBeInTheDocument();

    await user.click(row);
    // Выбор встречи уводит на саму форму счёта.
    expect(await screen.findByPlaceholderText('Например, 4000')).toBeInTheDocument();
  });

  it('объясняет пустой список вместо предложения непригодных встреч', async () => {
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/skladchinas/splittable-events`, () => HttpResponse.json([])),
    );
    renderPage('');

    expect(await screen.findByText(/Нет встреч, по которым можно разделить счёт/)).toBeInTheDocument();
  });
});
