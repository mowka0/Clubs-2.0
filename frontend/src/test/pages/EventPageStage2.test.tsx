import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

function stage2Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    title: 'Событие',
    description: null,
    locationText: 'Бар',
    eventDatetime: FUTURE,
    participantLimit: 10,
    votingOpensDaysBefore: 14,
    status: 'stage_2',
    goingCount: 3,
    maybeCount: 1,
    notGoingCount: 0,
    confirmedCount: 1,
    attendanceMarked: false,
    attendanceFinalized: false,
    createdAt: null,
    ...overrides,
  };
}

function mockEndpoints(opts: {
  event: EventDetailDto;
  myVote: string;
  responders?: EventResponderDto[];
  ownerId?: string;
}) {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: opts.myVote })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(opts.responders ?? [])),
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

describe('EventPage — Stage 2 window (Bug B) + expired status', () => {
  it('показывает кнопки подтверждения для stage_2 события до его начала', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'going' });
    renderEventPage();

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Подтвердить участие/ })).toBeInTheDocument();
  });

  it('скрывает кнопки подтверждения после старта события (статус ещё stage_2)', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: PAST }), myVote: 'going' });
    renderEventPage();

    // Страница загрузилась
    expect(await screen.findByText('Событие')).toBeInTheDocument();
    // Окно подтверждения закрыто — секции с кнопками нет
    expect(screen.queryByText('Подтверждение участия')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Подтвердить участие/ })).not.toBeInTheDocument();
  });

  it('на прошедшем событии «Кто идёт» = только confirmed; expired выпадает из состава и из отметки явки', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'u-confirmed', firstName: 'Анна', lastName: 'К', avatarUrl: null, status: 'confirmed', attendance: null },
      { userId: 'u-expired', firstName: 'Глеб', lastName: null, avatarUrl: null, status: 'expired_no_confirm', attendance: null },
    ];
    // Организатор смотрит прошедшее событие → виден блок отметки явки.
    mockEndpoints({
      event: stage2Event({ status: 'completed', eventDatetime: PAST }),
      myVote: 'confirmed',
      responders,
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    // Фазовый показ: с Этапа 2 «Кто идёт» = подтверждённый состав. «Бронь сгорела» (expired)
    // в состав не входит — Глеб не показывается нигде.
    expect(await screen.findByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByText('Глеб')).not.toBeInTheDocument();

    // И в чеклист отметки явки он тоже не попадает (только confirmed).
    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Анна К\./ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Глеб/ })).not.toBeInTheDocument();
  });

  it('фазовый счёт: после старта Этапа 2 отказавшийся не считается идущим', async () => {
    // Сценарий из теста: проголосовал «Пойду», на Этапе 2 «Отказался». Не должен попадать
    // ни в счётчик «Состав», ни в список «Кто идёт» — он больше не идёт.
    const responders: EventResponderDto[] = [
      { userId: 'u-confirmed', firstName: 'Анна', lastName: 'К', avatarUrl: null, status: 'confirmed', attendance: null },
      { userId: 'u-declined', firstName: 'Борис', lastName: null, avatarUrl: null, status: 'declined', attendance: null },
    ];
    mockEndpoints({
      // confirmedCount=1: один подтвердил, один отказался.
      event: stage2Event({ status: 'stage_2', confirmedCount: 1, goingCount: 2 }),
      myVote: 'declined',
      responders,
      ownerId: 'someone-else',
    });
    renderEventPage();

    // Заголовок состава считается по подтверждениям, а не по голосам Этапа 1.
    expect(await screen.findByText(/Состав · 1 \/ 10/)).toBeInTheDocument();
    // «Кто идёт» = только подтверждённые; отказавшийся выпал.
    expect(screen.getByText(/Кто идёт/)).toBeInTheDocument();
    expect(screen.queryByText('Борис')).not.toBeInTheDocument();
    expect(screen.getByText('Анна К.')).toBeInTheDocument();
  });
});
