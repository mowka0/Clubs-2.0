import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
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
// Порог отказа бэкенда (events.late-decline-threshold-minutes=240 = 4ч) с V83 не запрещает отказ,
// а делает его дороже: кнопка живёт до старта встречи, цену считает бэкенд (declineCostPoints).

function stage2Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  const eventDatetime = overrides.eventDatetime ?? FUTURE;
  return {
    id: EVENT_ID,
    clubId: CLUB_ID,
    creator: null,
    createdBy: VIEWER_ID,
    title: 'Событие',
    description: null,
    locationText: 'Бар',
    locationLat: null,
    locationLon: null,
    locationHint: null,
    eventDatetime,
    participantLimit: null,
    votingOpensDaysBefore: 14,
    status: 'stage_2',
    // «Сколько придёт»: подтверждение участия (кнопки «Подтвердить»/«Отказаться») осталось только
    // у этого формата. У форматов с лимитом (V85) состав закрывается голосами, и их экран
    // проверяет EventPageRoster.test.tsx.
    format: 'open',
    goingCount: 3,
    maybeCount: 1,
    notGoingCount: 0,
    confirmedCount: 1,
    noAnswerCount: 0,
    minParticipants: null,
    rosterDecided: false,
    declineConsequence: null,
    // По умолчанию дедлайн = дата события − 4ч (дефолт бэка): при FUTURE он в будущем (кнопка отказа
    // видна), при SOON — уже в прошлом (кнопка скрыта). Тест может переопределить явно.
    stage2LeadMinutes: null,
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

function mockEndpoints(opts: {
  event: EventDetailDto;
  myVote: string | null;
  mySeat?: string | null;
  responders?: EventResponderDto[];
  pending?: EventResponderDto[];
  ownerId?: string;
}) {
  const pending = opts.pending ?? [];
  server.use(
    http.get(`*/api/events/${EVENT_ID}`, () => HttpResponse.json(opts.event)),
    http.get(`*/api/events/${EVENT_ID}/my-vote`, () =>
      HttpResponse.json({ vote: opts.myVote, seat: opts.mySeat ?? null })),
    http.get(`*/api/events/${EVENT_ID}/responses`, () => HttpResponse.json(opts.responders ?? [])),
    http.get(`*/api/events/${EVENT_ID}/pending`, () => HttpResponse.json(pending)),
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

/**
 * Встреча с местами и закрытым составом — сценарии, где место дефицитно: счёт со знаменателем,
 * репутация, пояснения отметки явки. Базовая фикстура файла намеренно другая («сколько придёт»):
 * там проверяется само окно подтверждения, а не механика мест.
 */
function seatedEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
  return stage2Event({
    format: 'normal',
    participantLimit: 10,
    stage2LeadMinutes: 1080,
    rosterClosed: true,
    ...overrides,
  });
}

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

  it('подтверждённый: за <4ч до старта кнопка «Отказаться» ЕСТЬ — отказ стал платным (V83)', async () => {
    // Прежде кнопка здесь пряталась (запрет отказа внутри порога). Запрет снят: единственным
    // выходом оставалась молчаливая неявка за −200, что дороже любого честного отказа.
    mockEndpoints({
      event: stage2Event({ eventDatetime: SOON, declineCostPoints: 150 }),
      myVote: 'confirmed',
    });
    renderEventPage();

    expect(await screen.findByText('Подтверждение участия')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отказаться' })).toBeInTheDocument();
  });

  it('waitlisted видит «Отказаться» (выход из очереди) без порога', async () => {
    mockEndpoints({ event: stage2Event({ eventDatetime: SOON }), myVote: 'waitlisted' });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Отказаться' })).toBeInTheDocument();
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
      event: seatedEvent({ status: 'stage_2', confirmedCount: 1, goingCount: 2 }),
      myVote: 'declined',
      responders,
      ownerId: 'someone-else',
    });
    renderEventPage();

    // Заголовок состава считается по подтверждениям, а не по голосам Этапа 1.
    expect(await screen.findByText(/Места · 1 \/ 10/)).toBeInTheDocument();
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

  it('организатор видит кнопку «Отменить» на предстоящем событии', async () => {
    mockEndpoints({
      event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }),
      myVote: 'going',
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Отменить' })).toBeInTheDocument();
  });

  it('не-организатор не видит кнопку отмены', async () => {
    mockEndpoints({
      event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }),
      myVote: 'going',
      ownerId: 'someone-else',
    });
    renderEventPage();

    expect(await screen.findByText('Событие')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отменить' })).not.toBeInTheDocument();
  });

  it('«путь назад» (C): при просадке Trust в клубе события видна строка-мотиватор с проекцией', async () => {
    mockEndpoints({ event: seatedEvent({ eventDatetime: FUTURE }), myVote: 'going' });
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

  it('Этап 1 (upcoming): секция откликов озаглавлена «Кто откликнулся», не «Кто идёт»', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'g1', firstName: 'Гость', lastName: null, avatarUrl: null, status: 'going', attendance: null },
    ];
    mockEndpoints({ event: stage2Event({ status: 'upcoming', eventDatetime: FUTURE }), myVote: 'going', responders });
    renderEventPage();

    expect(await screen.findByText('Кто откликнулся')).toBeInTheDocument();
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
    // Карточка места свёрнута по умолчанию: карта и кнопки — после тапа по адресу.
    fireEvent.click(screen.getByRole('button', { name: /Покровка/ }));
    expect(screen.getByAltText('Карта места события')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Маршрут/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Открыть в Картах' })).toBeInTheDocument();
  });

  it('легаси-событие без координат: место текстом, без карты и кнопок', async () => {
    mockEndpoints({ event: seatedEvent({ eventDatetime: FUTURE }), myVote: 'going' });
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
    mockEndpoints({ event: seatedEvent({ eventDatetime: FUTURE }), myVote: 'going' });
    const { container } = renderEventPage();

    await screen.findByText('Событие');
    const heroBg = container.querySelector('.rd-hero-bg');
    expect(heroBg?.getAttribute('style') ?? '').not.toContain('background-image');
  });
});

/**
 * Легаси-открытые встречи, зависшие в `stage_2` до реформы v3 (event-formats.md § 16.7, группа B):
 * они доживают ровно по старым правилам — окно подтверждения, бесплатный отказ, отметка явки по
 * подтверждённым. Новая модель открытой встречи — в describe ниже.
 */
describe('EventPage — открытая встреча в stage_2 (легаси, § 16.7 группа B)', () => {
  // Открытая встреча: дедлайн отказа с бэка = старт события (порога нет).
  function openEvent(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
    const eventDatetime = overrides.eventDatetime ?? FUTURE;
    return stage2Event({
      participantLimit: null,
      format: 'open',
      // Последствие отказа называет сервер (V86 § 6): у открытой встречи — всегда `open`.
      declineConsequence: 'open',
      eventDatetime,
      ...overrides,
    });
  }

  it('бейдж хиро — «ОТКРЫТАЯ», счётчики без знаменателя', async () => {
    mockEndpoints({ event: openEvent({ confirmedCount: 7 }), myVote: 'going' });
    renderEventPage();

    expect(await screen.findByText('🌊 ОТКРЫТАЯ')).toBeInTheDocument();
    // Одно слово на весь жизненный цикл открытой (§ 16.8) и без « / limit».
    expect(screen.getByText('Идут · 7')).toBeInTheDocument();
    expect(screen.queryByText(/Идут · 7 \//)).not.toBeInTheDocument();
  });

  it('подтверждённый за <4ч до старта ВСЁ ЕЩЁ может отказаться — порога нет', async () => {
    // У события с лимитом при SOON кнопка скрыта (см. тест выше); у открытой встречи — видна,
    // потому что бэкенд отдаёт дедлайн = старту события.
    mockEndpoints({ event: openEvent({ eventDatetime: SOON }), myVote: 'confirmed' });
    renderEventPage();

    expect(await screen.findByRole('button', { name: 'Отказаться' })).toBeInTheDocument();
  });

  it('диалог отказа — «репутация не пострадает», без предупреждения о списании', async () => {
    mockEndpoints({ event: openEvent(), myVote: 'confirmed', responders: [] });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Отказаться' }));
    expect(screen.getByText(/репутация не пострадает/)).toBeInTheDocument();
    expect(screen.queryByText(/спишется 100 очков/)).not.toBeInTheDocument();
    // Кнопка действия — «Отказаться», не «Освободить» (мест нет).
    expect(screen.queryByRole('button', { name: 'Освободить' })).not.toBeInTheDocument();
  });

  it('«путь назад»: на открытой встрече строка-мотиватор скрыта (репутация за посещение не начисляется)', async () => {
    mockEndpoints({ event: openEvent(), myVote: 'going' });
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

    expect(await screen.findByText('🌊 ОТКРЫТАЯ')).toBeInTheDocument();
    expect(screen.queryByText(/надёжность вырастет/)).not.toBeInTheDocument();
  });

  it('отметка явки: орг видит пояснение, что отметка — только для истории (репутация не меняется)', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'u1', firstName: 'Анна', lastName: null, avatarUrl: null, status: 'confirmed', attendance: null },
    ];
    mockEndpoints({
      event: openEvent({ eventDatetime: PAST, status: 'stage_2' }),
      myVote: 'confirmed',
      responders,
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    expect(screen.getByText(/только для истории посещений/)).toBeInTheDocument();
    // Итерация 2 (PO): списаний нет вообще — старое предупреждение о −100 исчезло.
    expect(screen.queryByText(/спишется 100 очков/)).not.toBeInTheDocument();
  });

  it('у события с лимитом пояснения открытой встречи при отметке явки нет', async () => {
    const responders: EventResponderDto[] = [
      { userId: 'u1', firstName: 'Анна', lastName: null, avatarUrl: null, status: 'confirmed', attendance: null },
    ];
    mockEndpoints({
      event: seatedEvent({ eventDatetime: PAST }),
      myVote: 'confirmed',
      responders,
      ownerId: VIEWER_ID,
    });
    renderEventPage();

    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    expect(screen.queryByText(/только для истории посещений/)).not.toBeInTheDocument();
  });
});

/**
 * Модель v3 (event-formats.md § 16): открытая встреча одноэтапна. Голос «Пойду» сразу кладёт в
 * состав (`final_status = confirmed`), статуса `stage_2` у неё не бывает, голосование открыто до
 * самого старта, отказ — это голос «Не пойду» и он бесплатен.
 */
describe('EventPage — открытая встреча v3: одноэтапная (§ 16)', () => {
  /** Бэкенд после реформы отдаёт у открытой goingCount == confirmedCount и seat = confirmed. */
  function openV3Event(overrides: Partial<EventDetailDto> = {}): EventDetailDto {
    return stage2Event({
      status: 'upcoming',
      participantLimit: null,
      format: 'open',
      stage2LeadMinutes: null,
      rosterDeadline: null,
      declineConsequence: null,
      goingCount: 1,
      maybeCount: 0,
      confirmedCount: 1,
      ...overrides,
    });
  }

  function goingResponder(userId: string, firstName: string): EventResponderDto {
    return { userId, firstName, lastName: null, avatarUrl: null, status: 'going', seat: 'confirmed', attendance: null };
  }

  it('AC-OPEN1: голос «Пойду» и есть состав — подтверждать нечего', async () => {
    mockEndpoints({
      event: openV3Event(),
      myVote: 'going',
      mySeat: 'confirmed',
      responders: [goingResponder(VIEWER_ID, 'Пётр')],
    });
    renderEventPage();

    // Голос подсвечен на своей кнопке, человек в табе «Идут» и в заголовке состава.
    expect(await screen.findByRole('button', { name: /Пойду/ })).toHaveClass('rd-active');
    expect(screen.getByText('Идут · 1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Идут (1)' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Пётр')).toBeInTheDocument();
    // Ритуала подтверждения у открытой больше нет ни в каком виде.
    expect(screen.queryByText('Подтверждение участия')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Подтвердить участие/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отказаться' })).not.toBeInTheDocument();
    // Передумать можно той же тройкой кнопок — и полоса статуса говорит об этом прямо.
    expect(screen.getByRole('button', { name: /Не пойду/ })).toBeInTheDocument();
    expect(screen.getByText('Вы идёте')).toBeInTheDocument();
    expect(screen.getByText(/Передумать можно в любой момент/)).toBeInTheDocument();
  });

  it('не проголосовавший видит, что голосование открыто до начала встречи', async () => {
    mockEndpoints({ event: openV3Event({ goingCount: 0, confirmedCount: 0 }), myVote: null });
    renderEventPage();

    expect(await screen.findByText('Голосование открыто до начала встречи')).toBeInTheDocument();
    expect(screen.queryByText('Вы идёте')).not.toBeInTheDocument();
  });

  it('AC-OPEN3: отметить явку можно всем, кто голосовал «Пойду»', async () => {
    // Завершённая встреча: бэкенд отдаёт проголосовавших «Пойду» как состав (final_status).
    const responders: EventResponderDto[] = ['Анна', 'Борис', 'Вера'].map((name, i) => ({
      userId: `u${i}`, firstName: name, lastName: null, avatarUrl: null,
      status: 'confirmed', attendance: null,
    }));
    mockEndpoints({
      event: openV3Event({ status: 'completed', eventDatetime: PAST, goingCount: 3, confirmedCount: 3 }),
      myVote: 'confirmed',
      responders,
      ownerId: VIEWER_ID,
    });
    const { container } = renderEventPage();

    expect(await screen.findByText('Отметить посещаемость')).toBeInTheDocument();
    for (const name of ['Анна', 'Борис', 'Вера']) {
      expect(screen.getByRole('button', { name: new RegExp(`${name}: пришёл`) })).toBeInTheDocument();
    }
    // Словарь тот же, что до завершения: подтверждений не было, «Подтвердили» здесь соврало бы.
    expect(screen.getByText('Идут · 3')).toBeInTheDocument();
    expect(container.querySelector('.rd-st-confirmed')?.textContent).toContain('Идут');
    expect(screen.queryByText('Подтвердили')).not.toBeInTheDocument();
  });

  it('AC-OPEN7: после старта встречи голосовать уже нельзя', async () => {
    // Статус остаётся 'upcoming' ещё до шести часов после старта — окно закрывает дата, не статус.
    mockEndpoints({ event: openV3Event({ eventDatetime: PAST }), myVote: 'going' });
    renderEventPage();

    expect(await screen.findByText('Событие')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Пойду/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Не пойду/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Голосование открыто до начала встречи')).not.toBeInTheDocument();
  });

  it('AC-OPEN11: менеджер видит «Без ответа» и может напомнить до старта', async () => {
    mockEndpoints({
      event: openV3Event({ noAnswerCount: 1 }),
      myVote: 'going',
      mySeat: 'confirmed',
      responders: [goingResponder(VIEWER_ID, 'Пётр')],
      pending: [{
        userId: 'p1', firstName: 'Молчун', lastName: null, avatarUrl: null,
        status: 'no_answer', attendance: null, telegramUsername: 'silent', remindedAt: null,
      }],
      ownerId: VIEWER_ID,
    });
    const { user } = renderEventPage();

    await user.click(await screen.findByRole('button', { name: 'Без ответа (1)' }));

    expect(screen.getByText('Молчун')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Напомнить всем/ })).toBeInTheDocument();
  });

  it('участник «Без ответа» не видит: таб менеджерский', async () => {
    mockEndpoints({
      event: openV3Event({ noAnswerCount: 1 }),
      myVote: 'going',
      mySeat: 'confirmed',
      responders: [goingResponder(VIEWER_ID, 'Пётр')],
      pending: [{
        userId: 'p1', firstName: 'Молчун', lastName: null, avatarUrl: null,
        status: 'no_answer', attendance: null, remindedAt: null,
      }],
      ownerId: 'someone-else',
    });
    renderEventPage();

    expect(await screen.findByRole('button', { name: /Пойду/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Без ответа/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Молчун')).not.toBeInTheDocument();
  });
});
