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
const FUTURE = new Date(Date.now() + 2 * 86_400_000).toISOString();
const ROSTER_DEADLINE = new Date(Date.now() + 86_400_000).toISOString();

/**
 * Экран встречи формата «🎟 с местами» после перехода на порог набора
 * (docs/modules/event-roster-threshold.md § 3). Подтверждений у формата нет: место даёт голос,
 * состав закрывается сам, а цену отказа называет сервер.
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
    votingOpensDaysBefore: 14,
    status: 'upcoming',
    isUrgent: false,
    goingCount: 4,
    maybeCount: 3,
    notGoingCount: 1,
    confirmedCount: 4,
    noAnswerCount: 0,
    confirmedDeclineDeadline: ROSTER_DEADLINE,
    stage2LeadMinutes: 1080,
    stage2LeadMinutesOverride: null,
    abandonedSlotPenaltyPoints: 100,
    rosterDeadline: ROSTER_DEADLINE,
    rosterClosed: false,
    rosterShortfall: false,
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
  } as EventResponderDto;
}

function mockEndpoints(opts: {
  event: EventDetailDto;
  myVote: string | null;
  responders?: EventResponderDto[];
}) {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: opts.myVote })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(opts.responders ?? [])),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID,
      ownerId: 'someone-else',
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

describe('EventPage — порог набора (event-roster-threshold.md)', () => {
  it('AC-1: идёт набор — заголовок «Набор», кольцо считает состав, полоса называет недостачу', async () => {
    const { container } = renderEventPageWith({ event: rosterEvent(), myVote: 'going' });

    expect(await screen.findByText(/Набор · 4 \/ 6/)).toBeInTheDocument();
    expect(screen.getByText('в составе')).toBeInTheDocument();
    expect(container.querySelector('.rd-donut-num')).toHaveTextContent('4 / 6');
    expect(screen.getByText('Нужно ещё 2 человека')).toBeInTheDocument();
    expect(screen.getByText(/если не наберём, встреча не состоится/)).toBeInTheDocument();
  });

  it('AC-4: состав собран — заголовок «Состав», кнопки подтверждения нет', async () => {
    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, confirmedCount: 6 }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText(/Состав · 6 \/ 6/)).toBeInTheDocument();
    expect(screen.getByText('состав собран')).toBeInTheDocument();
    expect(screen.getByText('Состав собран — встреча состоится')).toBeInTheDocument();
    // Подтверждать нечего: место дал голос.
    expect(screen.queryByRole('button', { name: /Подтвердить участие/ })).not.toBeInTheDocument();
    expect(screen.getByText('Ваше участие')).toBeInTheDocument();
  });

  it('AC-9: цена отказа названа ДО открытия диалога и приходит с сервера', async () => {
    const { user } = renderEventPageWith({
      event: rosterEvent({
        status: 'stage_2', rosterClosed: true, confirmedCount: 6, declineCostPoints: 150,
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
        declineCostPoints: 0,
      }),
      myVote: 'confirmed',
      responders: [responder({ userId: VIEWER_ID, firstName: 'Я' })],
    });

    expect(await screen.findByText('Сейчас отказ бесплатен — вас заменит первый из очереди'))
      .toBeInTheDocument();
  });

  it('AC-2/AC-11: очередь — свой таб с номерами, выход из неё бесплатен', async () => {
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

  it('AC-5: недобор — экран говорит, что решает организатор', async () => {
    renderEventPageWith({
      event: rosterEvent({ rosterShortfall: true, confirmedCount: 4 }),
      myVote: 'going',
    });

    expect(await screen.findByText('Состав не набрался')).toBeInTheDocument();
    expect(screen.getByText(/Организатор решает/)).toBeInTheDocument();
  });

  it('AC-12: у срочной встречи механика подтверждения не тронута', async () => {
    renderEventPageWith({
      event: rosterEvent({ status: 'stage_2', rosterClosed: true, isUrgent: true }),
      myVote: 'going',
    });

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Подтвердить участие/ })).toBeInTheDocument();
    expect(screen.getByText('мест занято')).toBeInTheDocument();
  });
});

function renderEventPageWith(opts: {
  event: EventDetailDto;
  myVote: string | null;
  responders?: EventResponderDto[];
}) {
  mockEndpoints(opts);
  return renderEventPage();
}
