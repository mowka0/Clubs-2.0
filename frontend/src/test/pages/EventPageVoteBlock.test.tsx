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
const FUTURE = new Date(Date.now() + 86_400_000).toISOString();

/** Событие Этапа 1 с местами — база для тестов блока «Набор» (event-vote-block.md). */
function stage1Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
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
    participantLimit: 20,
    votingOpensDaysBefore: 14,
    status: 'upcoming',
    format: 'normal',
    goingCount: 2,
    maybeCount: 1,
    notGoingCount: 1,
    confirmedCount: 0,
    noAnswerCount: 0,
    minParticipants: null,
    rosterDecided: false,
    declineConsequence: null,
    stage2LeadMinutes: 1080,
    stage2LeadMinutesOverride: null,
    rosterDeadline: null,
    rosterClosed: false,
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

/** Отклики трёх статусов: бэкенд отдаёт `not_going` наравне с остальными. */
const RESPONDERS: EventResponderDto[] = [
  { userId: 'u1', firstName: 'Аня', lastName: 'Королёва', avatarUrl: null, status: 'going', attendance: null },
  { userId: 'u2', firstName: 'Игорь', lastName: null, avatarUrl: null, status: 'going', attendance: null },
  { userId: 'u3', firstName: 'Лиза', lastName: null, avatarUrl: null, status: 'maybe', attendance: null },
  { userId: 'u4', firstName: 'Тимур', lastName: null, avatarUrl: null, status: 'not_going', attendance: null },
];

function mockEndpoints(opts: { event: EventDetailDto; responders?: EventResponderDto[] }) {
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () => HttpResponse.json({ vote: null })),
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

describe('EventPage — блок «Набор» (event-vote-block.md)', () => {
  it('AC-VB1/VB2: кольцо считает СОСТАВ, «возможно» на него не влияет', async () => {
    // V83: у встречи с порогом набора голос «Иду» сразу кладёт в состав, поэтому кольцо считает
    // confirmedCount на обеих фазах, а подпись — «в составе» вместо «мест занято».
    mockEndpoints({
      event: stage1Event({ goingCount: 12, maybeCount: 5, notGoingCount: 2, confirmedCount: 12 }),
    });
    const { container } = renderEventPage();

    expect(await screen.findByText('в составе')).toBeInTheDocument();
    expect(container.querySelector('.rd-donut-num')).toHaveTextContent('12 / 20');

    // Дуга отмеряется stroke-dasharray = длина_окружности × going/лимит. 12/20 = 60%.
    const arc = container.querySelector('.rd-donut-arc');
    const circumference = 2 * Math.PI * 57;
    const [filled] = (arc?.getAttribute('stroke-dasharray') ?? '').split(' ').map(Number);
    expect(filled).toBeCloseTo(circumference * 0.6, 1);
  });

  it('AC-VB3: открытая — кольцо закрашено целиком, знаменателя нет', async () => {
    mockEndpoints({ event: stage1Event({ format: 'open', participantLimit: null, goingCount: 9, stage2LeadMinutes: null }) });
    const { container } = renderEventPage();

    expect(await screen.findByText('идут')).toBeInTheDocument();
    expect(screen.queryByText(/^\/ /)).not.toBeInTheDocument();

    const arc = container.querySelector('.rd-donut-arc');
    const circumference = 2 * Math.PI * 57;
    const [filled] = (arc?.getAttribute('stroke-dasharray') ?? '').split(' ').map(Number);
    expect(filled).toBeCloseTo(circumference, 1);
  });

  it('AC-VB5: таб «Не идут» показывает только проголосовавших not_going', async () => {
    mockEndpoints({ event: stage1Event(), responders: RESPONDERS });
    const { user } = renderEventPage();

    // По умолчанию открыт таб «Идут».
    expect(await screen.findByText('Аня К.')).toBeInTheDocument();
    expect(screen.queryByText('Тимур')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Не идут (1)' }));

    expect(await screen.findByText('Тимур')).toBeInTheDocument();
    expect(screen.queryByText('Аня К.')).not.toBeInTheDocument();
    expect(screen.queryByText('Лиза')).not.toBeInTheDocument();
  });

  it('AC-VB6: пустой таб → «Здесь пока пусто», строки-намёка новичка нет', async () => {
    mockEndpoints({
      event: stage1Event({ goingCount: 2, maybeCount: 0, notGoingCount: 0 }),
      responders: RESPONDERS.filter((r) => r.status === 'going'),
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Возможно (0)' }));

    expect(await screen.findByText('Здесь пока пусто.')).toBeInTheDocument();
    expect(screen.queryByText(/Пока никто не откликнулся/)).not.toBeInTheDocument();
  });

  it('AC-VB7: без единого отклика секции нет — остаётся строка-намёк', async () => {
    mockEndpoints({ event: stage1Event({ goingCount: 0, maybeCount: 0, notGoingCount: 0 }), responders: [] });
    renderEventPage();

    expect(await screen.findByText(/Пока никто не откликнулся/)).toBeInTheDocument();
    expect(screen.queryByText('Кто откликнулся')).not.toBeInTheDocument();
  });

  it('длинный список сворачивается: 6 строк + «Показать всех»', async () => {
    const many: EventResponderDto[] = Array.from({ length: 9 }, (_, i) => ({
      userId: `u${i}`, firstName: `Гость${i}`, lastName: null, avatarUrl: null,
      status: 'going', attendance: null,
    }));
    mockEndpoints({ event: stage1Event({ goingCount: 9 }), responders: many });
    const { user } = renderEventPage();

    expect(await screen.findByText('Гость5')).toBeInTheDocument();
    expect(screen.queryByText('Гость6')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Показать всех/ }));
    expect(await screen.findByText('Гость8')).toBeInTheDocument();
  });
});

describe('EventPage — бейдж формата встречи (event-formats.md § 9.1)', () => {
  it('обычная без минимума → «👥 ДО N»', async () => {
    mockEndpoints({ event: stage1Event() });
    renderEventPage();

    expect(await screen.findByText('👥 ДО 20')).toBeInTheDocument();
  });

  it('обычная с минимумом → «👥 MIN–MAX»', async () => {
    mockEndpoints({ event: stage1Event({ minParticipants: 4 }) });
    renderEventPage();

    expect(await screen.findByText('👥 4–20')).toBeInTheDocument();
  });

  it('открытая → «🌊 ОТКРЫТАЯ»', async () => {
    mockEndpoints({ event: stage1Event({ format: 'open', participantLimit: null, stage2LeadMinutes: null }) });
    renderEventPage();

    expect(await screen.findByText('🌊 ОТКРЫТАЯ')).toBeInTheDocument();
  });

  it('засечка минимума на кольце есть только при включённом минимуме', async () => {
    mockEndpoints({ event: stage1Event({ minParticipants: 4 }) });
    const { container, unmount } = renderEventPage();

    await screen.findByText('👥 4–20');
    expect(container.querySelector('.rd-roster-notch')).not.toBeNull();
    unmount();

    mockEndpoints({ event: stage1Event() });
    const second = renderEventPage();
    await screen.findByText('👥 ДО 20');
    expect(second.container.querySelector('.rd-roster-notch')).toBeNull();
  });
});
