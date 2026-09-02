import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { DeclineConsequence, EventDetailDto, EventResponderDto } from '../../types/api';

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

const VIEWER_ID = 'viewer-1';
const EVENT_ID = 'event-1';
const CLUB_ID = 'club-1';
const FUTURE = new Date(Date.now() + 2 * 86_400_000).toISOString();
const ROSTER_DEADLINE = new Date(Date.now() + 86_400_000).toISOString();

/**
 * Экран обычной встречи (docs/modules/event-formats.md): максимум всегда, минимум по
 * желанию. Подтверждений нет: место даёт голос, состав закрывается сам, цену и последствие
 * отказа называет сервер.
 */
function rosterEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    title: 'Настолка по четвергам',
    description: null,
    locationText: 'Кофейня «Дом»',
    locationLat: null,
    locationLon: null,
    locationHint: null,
    eventDatetime: FUTURE,
    participantLimit: 6,
    minParticipants: null,
    votingOpensDaysBefore: 14,
    status: 'upcoming',
    format: 'normal',
    goingCount: 4,
    maybeCount: 3,
    notGoingCount: 1,
    confirmedCount: 4,
    noAnswerCount: 0,
    stage2LeadMinutes: 1080,
    stage2LeadMinutesOverride: null,
    rosterDeadline: ROSTER_DEADLINE,
    rosterClosed: false,
    rosterDecided: false,
    waitlistedCount: 0,
    declineCostPoints: 0,
    declineConsequence: null,
    attendanceMarked: false,
    attendanceFinalized: false,
    cancellationReason: null,
    photoUrl: null,
    createdAt: null,
    ...overrides,
  };
}

/** Закрытый состав ниже минимума 4 — база сценариев «Проводим» и § 7.4. */
function belowMinimumEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return rosterEvent({
    status: 'stage_2', rosterClosed: true, minParticipants: 4, confirmedCount: 3,
    rosterDeadline: null, ...overrides,
  });
}

function responder(over: Partial<EventResponderDto> & { userId: string }): EventResponderDto {
  return {
    firstName: 'Имя',
    lastName: null,
    avatarUrl: null,
    status: 'confirmed',
    seat: null,
    attendance: null,
    ...over,
  } as EventResponderDto;
}

interface MockOptions {
  event: EventDetailDto;
  myVote: string | null;
  seat?: string | null;
  responders?: EventResponderDto[];
  /** Владелец клуба события; VIEWER_ID делает вызывающего менеджером. */
  ownerId?: string;
  pending?: EventResponderDto[];
}

function mockEndpoints(opts: MockOptions) {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(
      `*/api/events/${EVENT_ID}/my-vote`,
      () => HttpResponse.json({ vote: opts.myVote, seat: opts.seat ?? null }),
    ),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(opts.responders ?? [])),
    http.get(`*/api/events/${EVENT_ID}/pending`, () => HttpResponse.json(opts.pending ?? [])),
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

function renderEventPageWith(opts: MockOptions) {
  mockEndpoints(opts);
  return renderEventPage();
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

describe('EventPage — набор обычной встречи (event-formats.md § 9.1)', () => {
  it('с минимумом: заголовок «Набор», кольцо считает состав, полоса называет недостачу', async () => {
    const { container } = renderEventPageWith({
      event: rosterEvent({ minParticipants: 6 }), myVote: 'going',
    });

    expect(await screen.findByText(/Набор · 4 \/ 6/)).toBeInTheDocument();
    expect(screen.getByText('в составе')).toBeInTheDocument();
    expect(container.querySelector('.rd-donut-num')).toHaveTextContent('4 / 6');
    expect(screen.getByText('Нужно ещё 2 человека')).toBeInTheDocument();
    expect(screen.getByText(/если не наберём, встреча отменится/)).toBeInTheDocument();
    // Бейдж хиро капсом (regression V85): min = max читается «ровно», а не «6–6».
    expect(screen.getByText('👥 РОВНО 6')).toBeInTheDocument();
  });

  it('минимум набран, места есть — полоса говорит об обоих и отмену не обещает', async () => {
    renderEventPageWith({
      event: rosterEvent({ minParticipants: 4, confirmedCount: 4 }), myVote: 'going',
    });

    expect(await screen.findByText('Минимум набран · свободно 2 места')).toBeInTheDocument();
    expect(screen.getByText(/состав закроется тем, кто успел/)).toBeInTheDocument();
    expect(screen.queryByText(/встреча отменится/)).toBeNull();
  });

  it('мест нет — дальше очередь, даже при включённом минимуме', async () => {
    renderEventPageWith({
      event: rosterEvent({ minParticipants: 4, confirmedCount: 6, goingCount: 6 }),
      myVote: 'going', seat: 'confirmed',
    });

    expect(await screen.findByText('Мест нет — дальше очередь на замену')).toBeInTheDocument();
  });

  it('без минимума: полоса считает свободные места и отмену не обещает (AC-1)', async () => {
    renderEventPageWith({ event: rosterEvent({ confirmedCount: 4 }), myVote: 'going' });

    expect(await screen.findByText('Свободно 2 места')).toBeInTheDocument();
    expect(screen.getByText(/состав закроется тем, кто успел/)).toBeInTheDocument();
    expect(screen.queryByText(/встреча отменится/)).toBeNull();
    expect(screen.getByText('👥 ДО 6')).toBeInTheDocument();
  });

  it('голос при полном составе показывает очередь (V83)', async () => {
    // Голос «Иду» остаётся голосом — кнопка подсвечена и человек во вкладке «Идут», — но место
    // ушло в очередь, и полоса статуса обязана сказать об этом прямо.
    renderEventPageWith({
      event: rosterEvent({ confirmedCount: 6, goingCount: 7 }),
      myVote: 'going',
      seat: 'waitlisted',
    });

    expect(await screen.findByText('Мест уже нет — вы в очереди')).toBeInTheDocument();
    expect(screen.getByText(/место перейдёт вам/)).toBeInTheDocument();
  });

  it('очередь отделена чертой внутри вкладки «Идут» (PO 2026-08-31)', async () => {
    renderEventPageWith({
      event: rosterEvent({ participantLimit: 2, confirmedCount: 2, goingCount: 3, waitlistedCount: 1 }),
      myVote: 'going',
      seat: 'confirmed',
      responders: [
        responder({ userId: 'c1', firstName: 'Аня', status: 'going', seat: 'confirmed' }),
        responder({ userId: 'q1', firstName: 'Паша', status: 'going', seat: 'waitlisted' }),
      ],
    });

    expect(await screen.findByText('Аня')).toBeInTheDocument();
    expect(screen.getByText('В очереди на замену')).toBeInTheDocument();
  });
});

describe('EventPage — «Без ответа» на наборе (event-formats.md § 5)', () => {
  it('менеджер видит таб «Без ответа» с счётчиком с бэка и «Напомнить всем»', async () => {
    const remindCalls: unknown[] = [];
    server.use(
      http.post(`*/api/events/${EVENT_ID}/remind`, async ({ request }) => {
        remindCalls.push(await request.json());
        return HttpResponse.json({ remindedCount: 1 });
      }),
    );
    const { user } = renderEventPageWith({
      event: rosterEvent({ noAnswerCount: 1 }),
      myVote: 'going',
      ownerId: VIEWER_ID,
      responders: [responder({ userId: 'c1', firstName: 'Аня', status: 'going', seat: 'confirmed' })],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'maybe', telegramUsername: 'silent' })],
    });

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));
    expect(screen.getByText('Молчун')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Напомнить всем · 1/ }));
    await waitFor(() => expect(remindCalls).toHaveLength(1));
    expect(remindCalls[0]).toEqual({});
  });

  it('участник таба «Без ответа» на наборе не видит', async () => {
    renderEventPageWith({
      event: rosterEvent({ noAnswerCount: 1 }),
      myVote: 'going',
      responders: [responder({ userId: 'c1', firstName: 'Аня', status: 'going', seat: 'confirmed' })],
      pending: [responder({ userId: 'p1', firstName: 'Молчун', status: 'maybe' })],
    });

    expect(await screen.findByText('Аня')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Без ответа/ })).toBeNull();
  });
});

describe('EventPage — закрытый состав обычной встречи (event-formats.md § 7.4)', () => {
  it('состав собран — заголовок «Состав», кнопки подтверждения нет (AC-4)', async () => {
    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, confirmedCount: 6 }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText(/Состав · 6 \/ 6/)).toBeInTheDocument();
    expect(screen.getByText('состав собран')).toBeInTheDocument();
    expect(screen.getByText('Состав собран — встреча состоится')).toBeInTheDocument();
    // Подтверждать нечего: место дал голос. Отдельной секции «Ваше участие» тоже нет —
    // статус несут полоса над составом и точка на своём табе (решение PO 2026-08-31).
    expect(screen.queryByRole('button', { name: /Подтвердить участие/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Ваше участие')).not.toBeInTheDocument();
    expect(screen.queryByText('Подтверждение участия')).not.toBeInTheDocument();
  });

  it('тот, кто вне состава, всё ещё может занять место (AC-15)', async () => {
    const { unmount } = renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, confirmedCount: 5 }),
      myVote: null,
      responders: [],
    });
    expect(await screen.findByRole('button', { name: 'Занять свободное место' })).toBeInTheDocument();
    unmount();

    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, confirmedCount: 6 }),
      myVote: null,
      responders: [],
    });
    expect(await screen.findByRole('button', { name: 'Встать в очередь' })).toBeInTheDocument();
  });

  it('ниже минимума без «Проводим» — «состоится, если организатор не решит иначе»', async () => {
    renderEventPageWith({
      event: belowMinimumEvent(),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText(
      /Состав 3 из 4 — встреча состоится, если организатор не решит иначе/,
    )).toBeInTheDocument();
    expect(screen.queryByText('Состав собран — встреча состоится')).toBeNull();
    // Недостача считается от минимума, знаменатель кольца — от максимума.
    expect(screen.getByText(/Состав · 3 \/ 6/)).toBeInTheDocument();
  });

  it('ниже минимума после «Проводим» — «Проводим составом N»', async () => {
    renderEventPageWith({
      event: belowMinimumEvent({ rosterDecided: true }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText('Проводим составом 3')).toBeInTheDocument();
    expect(screen.queryByText(/если организатор не решит иначе/)).toBeNull();
  });

  it('минимум держится — тексты без изменений (AC-1)', async () => {
    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, minParticipants: 4, confirmedCount: 4 }),
      myVote: 'declined',
      responders: [],
    });

    expect(await screen.findByText('Вы отказались от места')).toBeInTheDocument();
  });

  it('состав закрыт и пуст — «состав собран» не обещаем ни с минимумом, ни без', async () => {
    // Именно это врало у «максимума» до правки PO 2026-09-01: полоса не смотрела на размер
    // состава и обещала встречу даже при нуле участников.
    const { unmount } = renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, confirmedCount: 0 }),
      myVote: null,
      responders: [],
    });
    expect(await screen.findByText('В составе никого')).toBeInTheDocument();
    expect(screen.queryByText('Состав собран — встреча состоится')).toBeNull();
    unmount();

    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, minParticipants: 6, confirmedCount: 0 }),
      myVote: null,
      responders: [],
    });
    expect(await screen.findByText('В составе никого')).toBeInTheDocument();
  });

  it('очередь — свой таб с номерами, выход из неё бесплатен (AC-2/AC-11)', async () => {
    const { user } = renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 6, waitlistedCount: 2,
      }),
      myVote: 'waitlisted',
      responders: [
        responder({ userId: 'c1', firstName: 'Аня' }),
        responder({ userId: 'q1', firstName: 'Паша', status: 'waitlisted' }),
        responder({ userId: VIEWER_ID, firstName: 'Я', status: 'waitlisted' }),
      ],
    });

    expect(await screen.findByText('Вы в очереди · 2-й')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Выйти из очереди' })).toBeInTheDocument();
    expect(screen.getByText('Выход из очереди бесплатен всегда — вы никого не держите'))
      .toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'В очереди (2)' }));
    expect(screen.getByText('Паша')).toBeInTheDocument();
  });
});

describe('EventPage — «Проводим» (event-formats.md § 4)', () => {
  const proceedCalls: string[] = [];
  beforeEach(() => {
    proceedCalls.length = 0;
    server.use(
      http.post(`*/api/events/${EVENT_ID}/proceed`, () => {
        proceedCalls.push('proceed');
        return HttpResponse.json(belowMinimumEvent({ rosterDecided: true }));
      }),
    );
  });

  it('менеджер видит кнопку при закрытом составе ниже минимума и зовёт /proceed через подтверждение', async () => {
    const { user } = renderEventPageWith({
      event: belowMinimumEvent(),
      myVote: null,
      ownerId: VIEWER_ID,
      responders: [responder({ userId: 'c1', firstName: 'Аня' })],
    });

    await user.click(await screen.findByRole('button', { name: 'Проводим' }));
    // Первый тап только раскрывает подтверждение — запроса ещё нет.
    expect(proceedCalls).toHaveLength(0);
    expect(screen.getByText(/Провести встречу составом 3\?/)).toBeInTheDocument();
    expect(screen.getByText(/Минимум и цена отказа не изменятся/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Проводим' }));
    await waitFor(() => expect(proceedCalls).toHaveLength(1));
  });

  it('ошибка сервера показывается текстом как есть', async () => {
    server.use(
      http.post(`*/api/events/${EVENT_ID}/proceed`, () =>
        HttpResponse.json({ message: 'Состав не ниже минимума — подтверждать нечего' }, { status: 400 })),
    );
    const { user } = renderEventPageWith({
      event: belowMinimumEvent(),
      myVote: null,
      ownerId: VIEWER_ID,
      responders: [],
    });

    await user.click(await screen.findByRole('button', { name: 'Проводим' }));
    await user.click(screen.getByRole('button', { name: 'Проводим' }));

    expect(await screen.findByText('Состав не ниже минимума — подтверждать нечего')).toBeInTheDocument();
  });

  it.each([
    ['отметка уже стоит', belowMinimumEvent({ rosterDecided: true })],
    ['состав не ниже минимума', belowMinimumEvent({ confirmedCount: 4 })],
    ['минимум выключен', belowMinimumEvent({ minParticipants: null })],
    ['набор ещё идёт', rosterEvent({ minParticipants: 4, confirmedCount: 3 })],
    ['встреча уже началась', belowMinimumEvent({ eventDatetime: new Date(Date.now() - 3_600_000).toISOString() })],
  ])('кнопки нет: %s', async (_label, event) => {
    renderEventPageWith({ event, myVote: null, ownerId: VIEWER_ID, responders: [] });

    expect(await screen.findByText('Настолка по четвергам')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проводим' })).toBeNull();
  });

  it('участнику кнопка не показывается', async () => {
    renderEventPageWith({
      event: belowMinimumEvent(),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText(/если организатор не решит иначе/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проводим' })).toBeNull();
  });
});

describe('EventPage — цена и последствие отказа (event-formats.md § 6)', () => {
  it('цена отказа названа ДО открытия диалога и приходит с сервера (AC-9)', async () => {
    const { user } = renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 6, declineCostPoints: 150,
        declineConsequence: 'seat_empty',
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText('Сейчас отказ стоит 150 очков репутации')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Не смогу прийти' }));
    expect(screen.getByText(/спишется 150 очков/)).toBeInTheDocument();
  });

  it('бесплатный отказ при живой очереди объясняет замену', async () => {
    renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 6, waitlistedCount: 2,
        declineCostPoints: 0, declineConsequence: 'replaced',
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText('Сейчас отказ бесплатен — вас заменит первый из очереди'))
      .toBeInTheDocument();
  });

  it('бесплатный отказ без замены (состав остаётся не ниже минимума) не обещает замену', async () => {
    renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, minParticipants: 4, confirmedCount: 6,
        declineCostPoints: 0, declineConsequence: 'seat_empty',
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText('Сейчас отказ бесплатен')).toBeInTheDocument();
    expect(screen.queryByText(/вас заменит первый из очереди/)).toBeNull();
  });

  it('внутри порога отказа кнопка НЕ прячется — отказ стал платным, а не запрещённым (AC-10)', async () => {
    // Регресс на баг V83: раньше фронт скрывал кнопку по дедлайну отказа, и единственным выходом
    // внутри 4 часов оставалась молчаливая неявка (−200) — ровно то, от чего уходили.
    const soon = new Date(Date.now() + 30 * 60_000).toISOString();
    renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 6,
        eventDatetime: soon, declineCostPoints: 150, declineConsequence: 'seat_empty',
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByRole('button', { name: 'Не смогу прийти' })).toBeInTheDocument();
    expect(screen.getByText('Сейчас отказ стоит 150 очков репутации')).toBeInTheDocument();
  });

  // Диалог берёт последствие у СЕРВЕРА, а не выводит из состава и очереди: одни и те же
  // цифры состава дают разный текст, если сервер назвал разное последствие.
  it.each<[DeclineConsequence, RegExp, string]>([
    ['replaced', /Его сразу займёт первый из очереди/, 'Освободить'],
    ['roster_empty', /Вы последний в составе\. Если освободите место, встреча будет отменена/, 'Отменить встречу'],
    ['below_minimum', /Состав станет меньше нужного — организатор решит, состоится ли встреча/, 'Освободить'],
    ['seat_empty', /Заменить вас некем — оно останется пустым/, 'Освободить'],
    ['open', /Это открытая встреча — репутация не пострадает/, 'Отказаться'],
  ])('диалог отказа при declineConsequence=%s', async (consequence, question, label) => {
    const { user } = renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 3, minParticipants: 4,
        declineCostPoints: 100, declineConsequence: consequence,
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    await user.click(await screen.findByRole('button', { name: 'Не смогу прийти' }));
    expect(screen.getByText(question)).toBeInTheDocument();
    // Цена дописывается к любому тексту, если не ноль.
    expect(screen.getByText(/спишется 100 очков/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
  });

  it('открытая встреча без изменений: подтверждение участия на месте (AC-12)', async () => {
    renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: false, format: 'open',
        participantLimit: null, stage2LeadMinutes: null, rosterDeadline: null,
      }),
      myVote: 'going',
    });

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Подтвердить участие/ })).toBeInTheDocument();
    expect(screen.getByText('🌊 ОТКРЫТАЯ')).toBeInTheDocument();
  });
});
