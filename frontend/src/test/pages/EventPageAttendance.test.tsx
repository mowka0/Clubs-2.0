import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { EventDetailDto, EventResponderDto, MyAttendanceDto } from '../../types/api';

// Мок Telegram SDK (той же формы, что в ClubPage.test.tsx)
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

import { EventPage } from '../../pages/EventPage';
import { useAuthStore } from '../../store/useAuthStore';

// Фикстурные id организатора-владельца, события и клуба — используются во всех моках теста
const OWNER_ID = 'user-1';
const EVENT_ID = 'event-1';
const CLUB_ID = 'club-1';
// Дата «сутки назад» — событие уже прошло, окно отметки явки открыто
const PAST = new Date(Date.now() - 86_400_000).toISOString();

function pastCompletedEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    title: 'Прошедшее событие',
    description: null,
    locationText: 'Бар',
    eventDatetime: PAST,
    participantLimit: 10,
    votingOpensDaysBefore: 14,
    status: 'completed',
    goingCount: 2,
    maybeCount: 0,
    notGoingCount: 1,
    confirmedCount: 1,
    attendanceMarked: false,
    attendanceFinalized: false,
    cancellationReason: null,
    createdAt: null,
    ...overrides,
  };
}

const RESPONDERS: EventResponderDto[] = [
  { userId: 'u-confirmed', firstName: 'Анна', lastName: 'К', avatarUrl: null, status: 'confirmed', attendance: null },
  { userId: 'u-confirmed2', firstName: 'Дмитрий', lastName: null, avatarUrl: null, status: 'confirmed', attendance: null },
  // Финальный ростер = только confirmed (PRD §4.4.3). Остальные ОБЯЗАНЫ быть исключены из
  // чеклиста явки: going = забыл подтвердить, expired = бронь сгорела, not_going.
  { userId: 'u-going', firstName: 'Борис', lastName: null, avatarUrl: null, status: 'going', attendance: null },
  { userId: 'u-expired', firstName: 'Глеб', lastName: null, avatarUrl: null, status: 'expired_no_confirm', attendance: null },
  { userId: 'u-no', firstName: 'Виктор', lastName: null, avatarUrl: null, status: 'not_going', attendance: null },
];

function mockEventEndpoints(opts: { ownerId: string; event?: EventDetailDto } = { ownerId: OWNER_ID }) {
  const event = opts.event ?? pastCompletedEvent();
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: 'confirmed' })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(RESPONDERS)),
    // F5-04: по умолчанию у вызывающего нет строки участия (организатор / не-участник) → 404.
    // Тесты, проверяющие UI спора участника, переопределяют это своим состоянием.
    http.get(`*/api/events/${EVENT_ID}/my-attendance`, () => new HttpResponse(null, { status: 404 })),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID,
      ownerId: opts.ownerId,
      name: 'Клуб',
      description: 'd',
      category: 'sport',
      accessType: 'open',
      city: 'Москва',
      district: null,
      memberLimit: 50,
      subscriptionPrice: 0,
      avatarUrl: null,
      rules: null,
      applicationQuestion: null,
      inviteLink: null,
      memberCount: 3,
      isActive: true,
    })),
  );
}

function renderEventPage() {
  const user = userEvent.setup();
  const result = renderWithProviders(
    <Routes>
      <Route path="/events/:id" element={<EventPage />} />
    </Routes>,
    { routerEntries: [`/events/${EVENT_ID}`] },
  );
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useAuthStore.setState({
    user: {
      id: OWNER_ID,
      telegramId: 1,
      telegramUsername: 'owner',
      firstName: 'Owner',
      lastName: null,
      avatarUrl: null,
      city: null,
      country: null,
      bio: null,
    },
    isAuthenticated: true,
    isLoading: false,
  } as never);
});

describe('EventPage — отметка посещаемости', () => {
  it('организатор после события видит блок и сохраняет посещаемость ТОЛЬКО для confirmed (не going/expired)', async () => {
    mockEventEndpoints({ ownerId: OWNER_ID });
    let postedBody: { attendance: { userId: string; attended: boolean }[] } | null = null;
    server.use(
      http.post(`*/api/events/${EVENT_ID}/attendance`, async ({ request }) => {
        postedBody = (await request.json()) as typeof postedBody;
        return HttpResponse.json({ eventId: EVENT_ID, markedCount: 2 });
      }),
    );

    const { user } = renderEventPage();

    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    // Только confirmed попадают в чеклист (как toggle-кнопки). Имена также есть в
    // «Кто идёт», поэтому ищем именно по роли button.
    expect(screen.getByRole('button', { name: /Анна К\./ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Дмитрий/ })).toBeInTheDocument();
    // «Забывшие подтвердить» (going/expired) и not_going — НЕ в финальном ростере.
    expect(screen.queryByRole('button', { name: /Борис/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Глеб/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Виктор/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Сохранить посещаемость/ }));

    await waitFor(() => expect(postedBody).not.toBeNull());
    expect(postedBody!.attendance).toHaveLength(2);
    // По умолчанию все отмечены пришедшими — организатор снимает галочку только с
    // отсутствующих (меньшинство; решение 2026-06-11, ревизия 2).
    expect(postedBody!.attendance.every((a) => a.attended)).toBe(true);
    expect(postedBody!.attendance.map((a) => a.userId).sort()).toEqual(['u-confirmed', 'u-confirmed2']);
    expect(await screen.findByText('Посещаемость отмечена')).toBeInTheDocument();
  });

  it('снятая галочка отправляет attended=false, не тронутые — attended=true', async () => {
    mockEventEndpoints({ ownerId: OWNER_ID });
    let postedBody: { attendance: { userId: string; attended: boolean }[] } | null = null;
    server.use(
      http.post(`*/api/events/${EVENT_ID}/attendance`, async ({ request }) => {
        postedBody = (await request.json()) as typeof postedBody;
        return HttpResponse.json({ eventId: EVENT_ID, markedCount: 2 });
      }),
    );

    const { user } = renderEventPage();
    await screen.findByText('Отметить посещаемость');

    // Снять отметку у Дмитрия (confirmed) — кликаем его toggle-кнопку
    await user.click(screen.getByRole('button', { name: /Дмитрий/ }));
    await user.click(screen.getByRole('button', { name: /Сохранить посещаемость/ }));

    await waitFor(() => expect(postedBody).not.toBeNull());
    const dmitry = postedBody!.attendance.find((a) => a.userId === 'u-confirmed2');
    expect(dmitry?.attended).toBe(false);
    // Анну не трогали — по умолчанию «пришла».
    const anna = postedBody!.attendance.find((a) => a.userId === 'u-confirmed');
    expect(anna?.attended).toBe(true);
  });

  it('после отметки организатор видит read-only статус без чеклиста', async () => {
    mockEventEndpoints({ ownerId: OWNER_ID, event: pastCompletedEvent({ attendanceMarked: true }) });
    renderEventPage();

    expect(await screen.findByText(/Посещаемость отмечена/)).toBeInTheDocument();
    expect(screen.queryByText('Отметить посещаемость')).not.toBeInTheDocument();
  });

  it('не-организатор не видит блок отметки', async () => {
    mockEventEndpoints({ ownerId: 'someone-else' });
    renderEventPage();

    // дождаться загрузки страницы
    expect(await screen.findByText('Прошедшее событие')).toBeInTheDocument();
    expect(screen.queryByText('Отметить посещаемость')).not.toBeInTheDocument();
  });

  it('организатор до даты события не видит блок отметки', async () => {
    const FUTURE = new Date(Date.now() + 86_400_000).toISOString();
    mockEventEndpoints({ ownerId: OWNER_ID, event: pastCompletedEvent({ status: 'upcoming', eventDatetime: FUTURE }) });
    renderEventPage();

    expect(await screen.findByText('Прошедшее событие')).toBeInTheDocument();
    expect(screen.queryByText('Отметить посещаемость')).not.toBeInTheDocument();
  });

  it('EXP-2: нейтрально закрытое событие (finalized, не marked) — окно истекло, чеклиста нет', async () => {
    mockEventEndpoints({
      ownerId: OWNER_ID,
      event: pastCompletedEvent({ attendanceMarked: false, attendanceFinalized: true }),
    });
    renderEventPage();

    expect(await screen.findByText(/Окно отметки явки истекло/)).toBeInTheDocument();
    // Бэкенд отклоняет позднюю отметку на finalized-событии, поэтому UI отметки должен исчезнуть.
    expect(screen.queryByText('Отметить посещаемость')).not.toBeInTheDocument();
  });

  it('организатор видит оспоренные отметки и резолвит «Пришёл»', async () => {
    mockEventEndpoints({
      ownerId: OWNER_ID,
      event: pastCompletedEvent({ attendanceMarked: true, attendanceFinalized: false }),
    });
    let resolveUrl: string | null = null;
    let resolveBody: { attended: boolean } | null = null;
    server.use(
      http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json([
        { userId: 'u-confirmed', firstName: 'Анна', lastName: 'К', avatarUrl: null, status: 'confirmed', attendance: 'disputed', disputeNote: 'Я был, отметьте заново' },
        { userId: 'u-confirmed2', firstName: 'Дмитрий', lastName: null, avatarUrl: null, status: 'confirmed', attendance: 'attended', disputeNote: null },
      ] satisfies EventResponderDto[])),
      http.post(`*/api/events/${EVENT_ID}/attendance/:userId/resolve`, async ({ request, params }) => {
        resolveUrl = String(params.userId);
        resolveBody = (await request.json()) as typeof resolveBody;
        return HttpResponse.json({ eventId: EVENT_ID, markedCount: 1 });
      }),
    );

    const { user } = renderEventPage();

    expect(await screen.findByText('Оспоренные отметки')).toBeInTheDocument();
    // Заметка участника видна организатору.
    expect(screen.getByText(/Я был, отметьте заново/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Пришёл' }));

    await waitFor(() => expect(resolveBody).not.toBeNull());
    expect(resolveUrl).toBe('u-confirmed');
    expect(resolveBody!.attended).toBe(true);
  });

  it('участник, отмеченный отсутствующим, видит «Оспорить» и оспаривает', async () => {
    // Смотрит участник, отмеченный отсутствующим, — НЕ организатор.
    useAuthStore.setState({
      user: {
        id: 'u-confirmed', telegramId: 2, telegramUsername: 'anna', firstName: 'Анна',
        lastName: 'К', avatarUrl: null, city: null, country: null, bio: null,
      },
      isAuthenticated: true,
      isLoading: false,
    } as never);
    mockEventEndpoints({
      ownerId: 'someone-else',
      event: pastCompletedEvent({ attendanceMarked: true, attendanceFinalized: false }),
    });
    let disputeBody: { note?: string } | null = null;
    server.use(
      http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json([
        { userId: 'u-confirmed', firstName: 'Анна', lastName: 'К', avatarUrl: null, status: 'confirmed', attendance: 'absent' },
      ] satisfies EventResponderDto[])),
      // F5-04: контролы спора теперь читают собственную явку вызывающего из /my-attendance
      // (доступен без членства в клубе). canDispute вычисляется на сервере.
      http.get(`*/api/events/${EVENT_ID}/my-attendance`, () => HttpResponse.json({
        attendance: 'absent', attendanceMarked: true, attendanceFinalized: false,
        disputeTerminal: false, canDispute: true, disputeNote: null,
      } satisfies MyAttendanceDto)),
      http.post(`*/api/events/${EVENT_ID}/dispute`, async ({ request }) => {
        disputeBody = (await request.json().catch(() => null)) as typeof disputeBody;
        return HttpResponse.json({ eventId: EVENT_ID, markedCount: 1 });
      }),
    );

    const { user } = renderEventPage();

    expect(await screen.findByText('Ваша явка')).toBeInTheDocument();
    // Необязательная заметка организатору уходит в теле запроса.
    await user.type(screen.getByPlaceholderText(/Комментарий организатору/), 'Я точно был');
    await user.click(screen.getByRole('button', { name: 'Оспорить' }));

    await waitFor(() => expect(disputeBody).not.toBeNull());
    expect(disputeBody!.note).toBe('Я точно был');
  });

  it('после терминального резолва участник видит «спор рассмотрен», кнопки «Оспорить» нет (F5-16)', async () => {
    useAuthStore.setState({
      user: {
        id: 'u-confirmed', telegramId: 2, telegramUsername: 'anna', firstName: 'Анна',
        lastName: 'К', avatarUrl: null, city: null, country: null, bio: null,
      },
      isAuthenticated: true,
      isLoading: false,
    } as never);
    mockEventEndpoints({
      ownerId: 'someone-else',
      event: pastCompletedEvent({ attendanceMarked: true, attendanceFinalized: false }),
    });
    server.use(
      http.get(`*/api/events/${EVENT_ID}/my-attendance`, () => HttpResponse.json({
        attendance: 'absent', attendanceMarked: true, attendanceFinalized: false,
        disputeTerminal: true, canDispute: false, disputeNote: null,
      } satisfies MyAttendanceDto)),
    );

    renderEventPage();

    expect(await screen.findByText(/Организатор рассмотрел ваш спор/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Оспорить' })).not.toBeInTheDocument();
  });
});
