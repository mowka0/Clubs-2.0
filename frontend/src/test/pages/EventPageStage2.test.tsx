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
const SOON = new Date(Date.now() + 2 * 3_600_000).toISOString(); // через 2ч < порога 4ч
// Дефолтный порог отказа бэкенда (events.stage2-decline-cutoff-minutes=240 = 4ч). Дедлайн отказа
// подтверждённого = eventDatetime − 4ч — то, что бэкенд кладёт в confirmedDeclineDeadline.
const DECLINE_CUTOFF_MS = 4 * 3_600_000;

function stage2Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  const eventDatetime = overrides.eventDatetime ?? FUTURE;
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    title: 'Событие',
    description: null,
    locationText: 'Бар',
    locationLat: null,
    locationLon: null,
    locationHint: null,
    eventDatetime,
    participantLimit: 10,
    votingOpensDaysBefore: 14,
    status: 'stage_2',
    goingCount: 3,
    maybeCount: 1,
    notGoingCount: 0,
    confirmedCount: 1,
    // По умолчанию дедлайн = дата события − 4ч (дефолт бэка): при FUTURE он в будущем (кнопка отказа
    // видна), при SOON — уже в прошлом (кнопка скрыта). Тест может переопределить явно.
    confirmedDeclineDeadline: new Date(new Date(eventDatetime).getTime() - DECLINE_CUTOFF_MS).toISOString(),
    attendanceMarked: false,
    attendanceFinalized: false,
    cancellationReason: null,
    photoUrl: null,
    createdAt: null,
    ...overrides,
  };
}

function mockEndpoints(opts: {
  event: EventDetailDto;
  myVote: string | null;
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

  it('Этап 2 открыт всем: not_going видит «Подтвердить участие», но без «Отказаться»', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'not_going' });
    renderEventPage();

    expect(await screen.findByRole('button', { name: /Подтвердить участие/ })).toBeInTheDocument();
    // «Отказаться» — только для going/maybe (им есть от чего отказываться); not_going его не видит
    expect(screen.queryByRole('button', { name: 'Отказаться' })).not.toBeInTheDocument();
  });

  it('Этап 2 открыт всем: не голосовавший (myVote null) тоже видит «Подтвердить участие»', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: null });
    renderEventPage();

    expect(await screen.findByRole('button', { name: /Подтвердить участие/ })).toBeInTheDocument();
  });

  it('подтверждённый (≥4ч): «Отказаться» → инлайн-подтверждение; без очереди предупреждает про репутацию', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'confirmed', responders: [] });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Отказаться' }));
    expect(screen.getByText(/Освободить место/)).toBeInTheDocument();
    expect(screen.getByText(/спишется 100 очков/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Освободить' })).toBeInTheDocument();
  });

  it('подтверждённый: при наличии очереди диалог обещает замену (без штрафа-предупреждения)', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'me', firstName: 'Я', lastName: null, avatarUrl: null, status: 'confirmed', attendance: null },
      { userId: 'w', firstName: 'Ждун', lastName: null, avatarUrl: null, status: 'waitlisted', attendance: null },
    ];
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'confirmed', responders });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Отказаться' }));
    expect(screen.getByText(/займёт первый из очереди/)).toBeInTheDocument();
    expect(screen.queryByText(/спишется 100 очков/)).not.toBeInTheDocument();
  });

  it('подтверждённый: за <4ч до старта кнопки «Отказаться» нет', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: SOON }), myVote: 'confirmed' });
    renderEventPage();

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отказаться' })).not.toBeInTheDocument();
  });

  it('waitlisted видит «Отказаться» (выход из очереди) без порога', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: SOON }), myVote: 'waitlisted' });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Отказаться' })).toBeInTheDocument();
  });

  it('Этап 2: лист ожидания рендерится в порядке приоритета с номерами позиций', async () => {
    // Бэкенд уже отдаёт респондеров по приоритету (stage_1_timestamp ASC); фронт сохраняет порядок.
    const responders: EventResponderDto[] = [
      { userId: 'u-conf', firstName: 'Анна', lastName: null, avatarUrl: null, status: 'confirmed', attendance: null },
      { userId: 'u-w1', firstName: 'Борис', lastName: null, avatarUrl: null, status: 'waitlisted', attendance: null },
      { userId: 'u-w2', firstName: 'Вера', lastName: null, avatarUrl: null, status: 'waitlisted', attendance: null },
    ];
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'confirmed', responders });
    const { container } = renderEventPage();

    // ждём по уникальной подсказке секции (заголовок «Лист ожидания» дублируется в сводке-счётчике)
    expect(await screen.findByText(/место получит первый в очереди/)).toBeInTheDocument();
    const rows = container.querySelectorAll('.rd-wl-row');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('.rd-wl-pos')?.textContent).toBe('1');
    expect(rows[0].textContent).toContain('Борис');
    expect(rows[1].querySelector('.rd-wl-pos')?.textContent).toBe('2');
    expect(rows[1].textContent).toContain('Вера');
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

describe('EventPage — отмена события (F5-14)', () => {
  it('отменённое событие показывает баннер с причиной и скрывает набор/голосование', async () => {
    mockEndpoints({
      event: stage2Event({ status: 'cancelled', cancellationReason: 'Площадка закрылась' }),
      myVote: 'going',
    });
    renderEventPage();

    const banner = await screen.findByText(/Событие отменено/);
    expect(banner.parentElement?.textContent).toContain('Площадка закрылась');
    // Набор/состав и голосование скрыты для отменённого события.
    expect(screen.queryByText(/Набор ·/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Состав ·/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Пойду/ })).not.toBeInTheDocument();
  });

  it('организатор видит кнопку «Отменить событие» на предстоящем событии', async () => {
    mockEndpoints({
      event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }),
      myVote: 'going',
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Отменить событие' })).toBeInTheDocument();
  });

  it('не-организатор не видит кнопку отмены', async () => {
    mockEndpoints({
      event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }),
      myVote: 'going',
      ownerId: 'someone-else',
    });
    renderEventPage();

    expect(await screen.findByText('Событие')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отменить событие' })).not.toBeInTheDocument();
  });

  it('«путь назад» (C): при просадке Trust в клубе события видна строка-мотиватор с проекцией', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'going' });
    server.use(
      http.get('*/api/users/me/reputation', () => HttpResponse.json({
        global: { reliableClubs: 0, trackRecordClubs: 1, score: 60 },
        activeClubs: [{
          clubId: CLUB_ID, clubName: 'Клуб', clubAvatarUrl: null, category: 'sport', role: 'member',
          joinedAt: null, trust: 60, promiseFulfillmentPct: 71, totalConfirmations: 9,
          totalAttendances: 7, spontaneityCount: 0, projectedNext1: 66, projectedNext2: 70,
          meetingsToReliable: 2, skladchinaPaid: 0, skladchinaTotal: 0, nearestEvent: null,
        }],
        historyClubs: [],
      })),
    );
    renderEventPage();

    expect(await screen.findByText(/надёжность вырастет/)).toBeInTheDocument();
    expect(screen.getByText('60')).toBeInTheDocument();
    expect(screen.getByText('66')).toBeInTheDocument();
  });

  it('«путь назад» (C): подтверждённый строку-мотиватор не видит (обещание уже дано)', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'confirmed' });
    server.use(
      http.get('*/api/users/me/reputation', () => HttpResponse.json({
        global: { reliableClubs: 0, trackRecordClubs: 1, score: 60 },
        activeClubs: [{
          clubId: CLUB_ID, clubName: 'Клуб', clubAvatarUrl: null, category: 'sport', role: 'member',
          joinedAt: null, trust: 60, promiseFulfillmentPct: 71, totalConfirmations: 9,
          totalAttendances: 7, spontaneityCount: 0, projectedNext1: 66, projectedNext2: 70,
          meetingsToReliable: 2, skladchinaPaid: 0, skladchinaTotal: 0, nearestEvent: null,
        }],
        historyClubs: [],
      })),
    );
    renderEventPage();

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.queryByText(/надёжность вырастет/)).not.toBeInTheDocument();
  });

  it('Этап 1 (upcoming): секция откликов озаглавлена «Предварительные голоса», не «Кто идёт»', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'g1', firstName: 'Гость', lastName: null, avatarUrl: null, status: 'going', attendance: null },
    ];
    mockEndpoints({ event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }), myVote: 'going', responders });
    renderEventPage();

    expect(await screen.findByText(/Предварительные голоса/)).toBeInTheDocument();
    expect(screen.queryByText(/Кто идёт/)).not.toBeInTheDocument();
  });
});

describe('EventPage — блок места (event-geo, кадр C)', () => {
  it('событие с координатами: карточка места с мини-картой, уточнением и кнопками маршрута', async () => {
    mockEndpoints({
      event: stage2Event({
        eventDatetime: FUTURE,
        locationText: 'ул. Покровка, 47/24с1, Москва',
        locationLat: 55.761216,
        locationLon: 37.646488,
        locationHint: 'Вход со двора, домофон 12',
      }),
      myVote: 'going',
    });
    renderEventPage();

    expect(await screen.findByText('ул. Покровка, 47/24с1, Москва')).toBeInTheDocument();
    expect(screen.getByText('Вход со двора, домофон 12')).toBeInTheDocument();
    expect(screen.getByAltText('Карта места события')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Маршрут/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Открыть в Картах' })).toBeInTheDocument();
  });

  it('легаси-событие без координат: место текстом, без карты и кнопок', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'going' });
    renderEventPage();

    expect(await screen.findByText('Бар')).toBeInTheDocument();
    expect(screen.queryByAltText('Карта места события')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Маршрут/ })).not.toBeInTheDocument();
  });

  it('уточнение к месту видно и без гео-точки: адрес + серое уточнение', async () => {
    mockEndpoints({
      event: stage2Event({ eventDatetime: FUTURE, locationHint: 'Вход со двора, домофон 12' }),
      myVote: 'going',
    });
    renderEventPage();

    expect(await screen.findByText('Бар')).toBeInTheDocument();
    expect(screen.getByText('Вход со двора, домофон 12')).toBeInTheDocument();
  });

  it('hint-only событие (места нет): уточнение показано как место', async () => {
    mockEndpoints({
      event: stage2Event({ eventDatetime: FUTURE, locationText: null, locationHint: 'Встречаемся в зуме' }),
      myVote: 'going',
    });
    renderEventPage();

    expect(await screen.findByText('Встречаемся в зуме')).toBeInTheDocument();
    expect(screen.queryByAltText('Карта места события')).not.toBeInTheDocument();
  });
});

describe('EventPage — фото события как фон хиро', () => {
  it('фото события задано — оно фон хиро (не аватар клуба)', async () => {
    mockEndpoints({
      event: stage2Event({ eventDatetime: FUTURE, photoUrl: 'https://cdn.example.com/event-cover.jpg' }),
      myVote: 'going',
    });
    const { container } = renderEventPage();

    await screen.findByText('Событие');
    const heroBg = container.querySelector('.rd-hero-bg');
    expect(heroBg).toHaveStyle({ backgroundImage: 'url(https://cdn.example.com/event-cover.jpg)' });
  });

  it('без фото — фолбэк на аватар клуба отсутствует у клуба без аватарки (без backgroundImage)', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: FUTURE }), myVote: 'going' });
    const { container } = renderEventPage();

    await screen.findByText('Событие');
    const heroBg = container.querySelector('.rd-hero-bg');
    expect(heroBg?.getAttribute('style') ?? '').not.toContain('background-image');
  });
});
