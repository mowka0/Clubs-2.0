import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { EventDetailDto, EventResponderDto } from '../../types/api';

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
const PAST = new Date(Date.now() - 86_400_000).toISOString();
const FUTURE = new Date(Date.now() + 86_400_000).toISOString();

function buildEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
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
    status: 'upcoming',
    goingCount: 0,
    maybeCount: 0,
    notGoingCount: 0,
    confirmedCount: 0,
    confirmedDeclineDeadline: PAST, abandonedSlotPenaltyPoints: 100, stage2LeadMinutes: 1080,
    attendanceMarked: false,
    attendanceFinalized: false,
    cancellationReason: null,
    photoUrl: null,
    createdAt: null,
    ...overrides,
  };
}

interface MockOpts {
  event: EventDetailDto;
  myVote?: string | null;
  responders?: EventResponderDto[];
  respondersStatus?: number; // для проверки гейта isSuccess: не-200 → строки нет
  ownerId?: string;
}

function mockEndpoints(opts: MockOpts) {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: opts.myVote ?? null })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () =>
      opts.respondersStatus && opts.respondersStatus !== 200
        ? new HttpResponse(null, { status: opts.respondersStatus })
        : HttpResponse.json(opts.responders ?? []),
    ),
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
  const result = renderWithProviders(
    <Routes>
      <Route path="/events/:id" element={<EventPage />} />
    </Routes>,
    { routerEntries: [`/events/${EVENT_ID}`] },
  );
  return result;
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

describe('EventPage — W3-09 строка-намёк при 0 откликах (Этап 1)', () => {
  it('участник: свежее событие без голосов → «Пока никто не откликнулся…»', async () => {
    mockEndpoints({ event: buildEvent(), responders: [], ownerId: 'someone-else' });
    renderEventPage();

    expect(await screen.findByText(/Пока никто не откликнулся/)).toBeInTheDocument();
    // Вариант организатора не показывается участнику.
    expect(screen.queryByText(/Поделись событием в чате клуба/)).not.toBeInTheDocument();
  });

  it('организатор: то же событие → вариант про чат клуба', async () => {
    mockEndpoints({ event: buildEvent(), responders: [], ownerId: VIEWER_ID });
    renderEventPage();

    expect(await screen.findByText(/Голосов пока нет\. Поделись событием в чате клуба/)).toBeInTheDocument();
    expect(screen.queryByText(/Пока никто не откликнулся/)).not.toBeInTheDocument();
  });

  it('появился первый голос → строки нет, есть «Предварительные голоса · 1»', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'g1', firstName: 'Гость', lastName: null, avatarUrl: null, status: 'going', attendance: null },
    ];
    mockEndpoints({ event: buildEvent({ goingCount: 1 }), responders, ownerId: 'someone-else' });
    renderEventPage();

    expect(await screen.findByText(/Предварительные голоса/)).toBeInTheDocument();
    expect(screen.queryByText(/Пока никто не откликнулся/)).not.toBeInTheDocument();
  });

  it('Этап 2 (stage_2): строки-намёка нет (гейт showVoting)', async () => {
    mockEndpoints({
      event: buildEvent({ status: 'stage_2', eventDatetime: FUTURE }),
      responders: [],
      ownerId: 'someone-else',
    });
    renderEventPage();

    expect(await screen.findByText(/Состав ·/)).toBeInTheDocument();
    expect(screen.queryByText(/Пока никто не откликнулся/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Голосов пока нет/)).not.toBeInTheDocument();
  });

  it('responders упали (5xx): строки нет — ложная пустота недопустима (F5-20)', async () => {
    // Организатор, чтобы 5xx не спутать с 403-редиректом не-участника.
    mockEndpoints({ event: buildEvent(), respondersStatus: 500, ownerId: VIEWER_ID });
    renderEventPage();

    // Страница загрузилась (блок набора виден), но строки-намёка нет — responders не isSuccess.
    expect(await screen.findByText(/Набор ·/)).toBeInTheDocument();
    expect(screen.queryByText(/Голосов пока нет/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Пока никто не откликнулся/)).not.toBeInTheDocument();
  });
});

describe('EventPage — W3-10 «Отмечать некого» при 0 подтверждённых', () => {
  it('организатор, прошедшее событие, 0 подтверждённых → новый текст, без кнопки сохранения', async () => {
    mockEndpoints({
      event: buildEvent({ status: 'completed', eventDatetime: PAST }),
      myVote: 'confirmed',
      responders: [], // 0 confirmed
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    expect(
      screen.getByText('Отмечать некого — никто не подтвердил участие в этом событии.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Сохранить посещаемость/ })).not.toBeInTheDocument();
  });
});
