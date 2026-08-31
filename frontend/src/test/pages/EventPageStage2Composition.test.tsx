import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { EventDetailDto, EventResponderDto, MembershipDto } from '../../types/api';

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

// Открытие личного чата — единственный внешний эффект строки «Без ответа», мокаем его целиком.
const { openTmeLinkMock } = vi.hoisted(() => ({ openTmeLinkMock: vi.fn() }));
vi.mock('../../utils/telegramLinks', () => ({ openTmeLink: openTmeLinkMock }));

import { EventPage } from '../../pages/EventPage';
import { useAuthStore } from '../../store/useAuthStore';

const VIEWER_ID = 'viewer-1';
const EVENT_ID = 'event-1';
const CLUB_ID = 'club-1';
const FUTURE = new Date(Date.now() + 86_400_000).toISOString();

function stage2Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    title: 'Событие',
    description: null,
    locationText: 'Бар',
    locationLat: null,
    locationLon: null,
    locationHint: null,
    eventDatetime: FUTURE,
    participantLimit: 10,
    votingOpensDaysBefore: 14,
    status: 'stage_2',
    format: 'max',
    goingCount: 3,
    maybeCount: 1,
    notGoingCount: 0,
    confirmedCount: 1,
    noAnswerCount: 0,
    stage2LeadMinutes: 1080,
    stage2LeadMinutesOverride: null,
    rosterDeadline: null,
    rosterClosed: true,
    waitlistedCount: 0,
    declineCostPoints: 0,
    attendanceMarked: false,
    attendanceFinalized: false,
    cancellationReason: null,
    photoUrl: null,
    createdAt: null,
    ...overrides,
  };
}

function responder(over: Partial<EventResponderDto> & { userId: string }): EventResponderDto {
  return {
    firstName: 'Имя',
    lastName: null,
    avatarUrl: null,
    status: 'confirmed',
    attendance: null,
    ...over,
  };
}

function membership(role: MembershipDto['role'], status = 'active'): MembershipDto {
  return {
    id: 'm-1', userId: VIEWER_ID, clubId: CLUB_ID, status, role,
    joinedAt: null, subscriptionExpiresAt: null,
  };
}

function mockEndpoints(opts: {
  event?: EventDetailDto;
  myVote?: string | null;
  responders?: EventResponderDto[];
  ownerId?: string;
  myClubs?: MembershipDto[];
  pending?: EventResponderDto[];
}) {
  const pending = opts.pending ?? [];
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () =>
      HttpResponse.json({ ...(opts.event ?? stage2Event()), noAnswerCount: pending.length })),
    http.get(`*/api/events/${EVENT_ID}/pending`, () => HttpResponse.json(pending)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: opts.myVote ?? 'confirmed' })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(opts.responders ?? [])),
    http.get('*/api/users/me/clubs', () => HttpResponse.json(opts.myClubs ?? [])),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID,
      ownerId: opts.ownerId ?? 'someone-else',
      name: 'Клуб', description: 'd', category: 'sport', accessType: 'open', city: 'Москва',
      district: null, memberLimit: 50, subscriptionPrice: 0, avatarUrl: null, rules: null,
      applicationQuestion: null, inviteLink: null, memberCount: 3, isActive: true,
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
afterEach(() => { server.resetHandlers(); openTmeLinkMock.mockClear(); });
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

describe('EventPage — состав Этапа 2 в стиле Этапа 1 (event-stage2-composition.md)', () => {
  it('AC-S1/S2: сводка — немые плитки, а не строки «ключ — значение»', async () => {
    mockEndpoints({
      responders: [
        responder({ userId: 'c1' }),
        responder({ userId: 'w1', status: 'waitlisted' }),
      ],
      pending: [responder({ userId: 'p1', status: 'going' })],
    });
    const { container } = renderEventPage();

    // V83: у встречи с порогом набора плитка называется «В составе» — подтверждений нет.
    expect(await screen.findByText('В составе')).toBeInTheDocument();
    const tiles = [...container.querySelectorAll('.rd-stat-tile')];
    // Подписи читаем внутри плиток: «В очереди» дублируется заголовком секции ниже.
    expect(tiles.map((t) => t.querySelector('.rd-vl')?.textContent))
      .toEqual(['В составе', 'В очереди', 'Возможно']);
    // Плитки не кнопки: на Этапе 2 счётчики никуда не ведут.
    tiles.forEach((tile) => expect(tile.tagName).toBe('DIV'));
    expect(container.querySelector('.rd-kv')).toBeNull();
  });

  it('AC-S3: длинный состав сворачивается до 6 строк с «Показать всех»', async () => {
    const confirmed = Array.from({ length: 8 }, (_, i) =>
      responder({ userId: `c${i}`, firstName: `Гость${i}` }));
    mockEndpoints({ responders: confirmed });
    const { container, user } = renderEventPage();

    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(container.querySelectorAll('.rd-resp-row')).toHaveLength(6);

    await user.click(screen.getByRole('button', { name: /Показать всех · 8/ }));
    expect(container.querySelectorAll('.rd-resp-row')).toHaveLength(8);
  });

  it('AC-S4: обычный участник не видит переключателя и имён не ответивших', async () => {
    mockEndpoints({
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'going' })],
    });
    renderEventPage();

    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Без ответа \(/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Молчун')).not.toBeInTheDocument();
  });

  it('AC-S5: владелец видит два таба, по умолчанию открыт «Идут»', async () => {
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'going' })],
    });
    renderEventPage();

    const goingTab = await screen.findByRole('button', { name: 'Кто идёт (1)' });
    expect(goingTab).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Без ответа (1)' })).toHaveAttribute('aria-pressed', 'false');
    // Пока открыт «Идут» — молчуна в списке нет.
    expect(screen.queryByText('Молчун')).not.toBeInTheDocument();
  });

  it('AC-S5: активный со-организатор получает те же табы, что владелец', async () => {
    mockEndpoints({
      myClubs: [membership('co_organizer')],
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'going' })],
    });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Без ответа (1)' })).toBeInTheDocument();
  });

  it('со-организатор без активного членства табов не получает (fail-close)', async () => {
    mockEndpoints({
      myClubs: [membership('co_organizer', 'frozen')],
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'going' })],
    });
    renderEventPage();

    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Без ответа \(/ })).not.toBeInTheDocument();
  });

  it('AC-S6: все ответили — таб на месте и объясняет, что напоминать некому', async () => {
    // Видимость постоянная (решение PO 2026-08-16): иначе организатор не узнает о механике.
    mockEndpoints({ ownerId: VIEWER_ID, responders: [responder({ userId: 'c1', firstName: 'Анна' })] });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (0)' }));
    expect(screen.getByText('Все ответили — напоминать некому.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Напомнить всем/ })).not.toBeInTheDocument();
  });

  it('AC-S7: тап по молчуну с username открывает личный чат', async () => {
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Пётр', status: 'going', telegramUsername: 'petr_s' })],
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    expect(screen.getByText('@petr_s · пойду')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Написать Пётр/ }));
    expect(openTmeLinkMock).toHaveBeenCalledWith('https://t.me/petr_s');
  });

  it('AC-S8: без username строка не кликабельна и чат не открывается', async () => {
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Наталья', status: 'maybe', telegramUsername: null })],
    });
    const { container, user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    expect(screen.getByText('без username · возможно')).toBeInTheDocument();

    const row = container.querySelector('.rd-pend-main') as HTMLButtonElement;
    expect(row.disabled).toBe(true);
    await user.click(row);
    expect(openTmeLinkMock).not.toHaveBeenCalled();
  });

  it('username с посторонними символами не превращается в ссылку', async () => {
    // Значение приходит из БД и подставляется в URL: «petr/../evil?x=1» увёл бы тап на чужой
    // адрес. Формат не прошёл проверку → строка ведёт себя как «username нет».
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Кривой', status: 'going', telegramUsername: 'petr/../evil?x=1' })],
    });
    const { container, user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    expect(screen.getByText('без username · пойду')).toBeInTheDocument();

    const row = container.querySelector('.rd-pend-main') as HTMLButtonElement;
    expect(row.disabled).toBe(true);
    await user.click(row);
    expect(openTmeLinkMock).not.toHaveBeenCalled();
  });

  it('AC-S9: никто не подтвердил, но молчуны есть — менеджер всё равно видит секцию', async () => {
    mockEndpoints({
      ownerId: VIEWER_ID,
      myVote: 'going',
      event: stage2Event({ confirmedCount: 0 }),
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'going' })],
    });
    const { user } = renderEventPage();

    expect(await screen.findByRole('button', { name: 'Кто идёт (0)' })).toBeInTheDocument();
    expect(screen.getByText('В составе пока никого.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Без ответа (1)' }));
    expect(screen.getByText('Молчун')).toBeInTheDocument();
  });

  it('колокольчик отправляет напоминание конкретному участнику', async () => {
    let body: unknown = null;
    server.use(http.post(`*/api/events/${EVENT_ID}/remind`, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ remindedCount: 1 });
    }));
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Пётр', status: 'going', telegramUsername: 'petr_s' })],
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    await user.click(screen.getByRole('button', { name: 'Напомнить Пётр' }));

    await screen.findByText(/Напоминание отправлено · 1/);
    expect(body).toEqual({ userId: 'p1' });
  });

  it('уже напомненному колокольчик заблокирован, в мете — время отправки', async () => {
    const remindedAt = new Date('2026-08-16T18:42:00Z').toISOString();
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({
        userId: 'p1', firstName: 'Кирилл', status: 'going',
        telegramUsername: 'kirill', remindedAt,
      })],
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    const bell = screen.getByRole('button', { name: /Напоминание отправлено: Кирилл/ }) as HTMLButtonElement;
    expect(bell.disabled).toBe(true);
    expect(screen.getByText(/напомнили в/)).toBeInTheDocument();
    // Всем уже напомнили — массовой кнопки нет.
    expect(screen.queryByRole('button', { name: /Напомнить всем/ })).not.toBeInTheDocument();
  });

  it('«Напомнить всем» считает только тех, кому ещё не напоминали, и шлёт запрос без userId', async () => {
    let body: unknown = 'not-called';
    server.use(http.post(`*/api/events/${EVENT_ID}/remind`, async ({ request }) => {
      body = await request.json();
      return HttpResponse.json({ remindedCount: 2 });
    }));
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
        responder({ userId: 'p1', firstName: 'Пётр', status: 'going', telegramUsername: 'petr_s' }),
      ],
      pending: [
        responder({ userId: 'p1', firstName: 'Пётр', status: 'going', telegramUsername: 'petr_s' }),
        responder({ userId: 'p2', firstName: 'Мария', status: 'maybe' }),
        responder({ userId: 'p3', firstName: 'Кирилл', status: 'going', remindedAt: new Date().toISOString() }),
      ],
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (3)' }));
    // Трое молчунов, но одному уже напомнили → адресатов двое.
    await user.click(screen.getByRole('button', { name: /Напомнить всем · 2/ }));

    await screen.findByText(/Напоминание отправлено · 2/);
    expect(body).toEqual({});
  });

  it('нулевой ответ сервера не выдаётся за отправку', async () => {
    server.use(http.post(`*/api/events/${EVENT_ID}/remind`, () =>
      HttpResponse.json({ remindedCount: 0 })));
    mockEndpoints({
      ownerId: VIEWER_ID,
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
      ],
      pending: [responder({ userId: 'p1', firstName: 'Пётр', status: 'going', telegramUsername: 'petr_s' })],
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    await user.click(screen.getByRole('button', { name: 'Напомнить Пётр' }));

    expect(await screen.findByText('Всем, кому можно, уже напомнили')).toBeInTheDocument();
  });

  it('участник не видит кнопок напоминания', async () => {
    mockEndpoints({
      responders: [
        responder({ userId: 'c1', firstName: 'Анна' }),
        responder({ userId: 'p1', firstName: 'Пётр', status: 'going' }),
      ],
    });
    renderEventPage();

    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Напомнить/ })).not.toBeInTheDocument();
  });

  it('на завершённой встрече таба нет — напоминать уже поздно', async () => {
    const PAST = new Date(Date.now() - 86_400_000).toISOString();
    mockEndpoints({
      ownerId: VIEWER_ID,
      event: stage2Event({ status: 'completed', eventDatetime: PAST }),
      responders: [responder({ userId: 'c1', firstName: 'Анна' })],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'no_answer' })],
    });
    renderEventPage();

    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Без ответа \(/ })).not.toBeInTheDocument();
  });

  it('переключение таба сбрасывает раскрытие длинного списка', async () => {
    const responders = [
      ...Array.from({ length: 8 }, (_, i) => responder({ userId: `c${i}`, firstName: `Гость${i}` })),
    ];
    const pending = Array.from({ length: 7 }, (_, i) =>
      responder({ userId: `p${i}`, firstName: `Молчун${i}`, status: 'going' }));
    mockEndpoints({ ownerId: VIEWER_ID, responders, pending });
    const { container, user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: /Показать всех · 8/ }));
    expect(container.querySelectorAll('.rd-resp-row')).toHaveLength(8);

    await user.click(screen.getByRole('button', { name: 'Без ответа (7)' }));
    // Список молчунов открывается свёрнутым, а не унаследовав раскрытие соседнего таба.
    expect(container.querySelectorAll('.rd-pend-row')).toHaveLength(6);
  });
});
