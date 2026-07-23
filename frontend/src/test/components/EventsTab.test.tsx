import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { MembershipDto, PageResponse, MyEventListItemDto } from '../../types/api';

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { EventsTab } from '../../components/activities/EventsTab';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';

const CLUB_ID = 'club-1';
const NOW = Date.now();
const DAY = 24 * 60 * 60 * 1000;

/** Пустой фид событий (query success, 0 событий). */
const EMPTY_FEED: PageResponse<MyEventListItemDto> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 20,
};

function baseEvent(over: Partial<MyEventListItemDto> = {}): MyEventListItemDto {
  return {
    id: 'e-1',
    title: 'Событие',
    eventDatetime: new Date(NOW + DAY).toISOString(),
    locationText: 'Кафе',
    photoUrl: null,
    status: 'upcoming',
    clubId: CLUB_ID,
    clubName: 'Шахматы',
    clubAvatarUrl: null,
    myVote: null,
    myParticipationStatus: null,
    goingCount: 0,
    confirmedCount: 0,
    participantLimit: 10,
    isUrgent: false,
    actionRequired: false,
    isHistory: false,
    ...over,
  };
}

/** Прошедшее посещённое событие для секции «История». */
function historyEvent(over: Partial<MyEventListItemDto> = {}): MyEventListItemDto {
  return baseEvent({
    id: 'e-hist',
    title: 'Прошлая встреча',
    eventDatetime: new Date(NOW - DAY).toISOString(),
    status: 'completed',
    myParticipationStatus: 'confirmed',
    isHistory: true,
    ...over,
  });
}

function feed(content: MyEventListItemDto[]): PageResponse<MyEventListItemDto> {
  return { ...EMPTY_FEED, content, totalElements: content.length, totalPages: content.length ? 1 : 0 };
}

function membership(over: Partial<MembershipDto> = {}): MembershipDto {
  return {
    id: 'm-1',
    userId: 'viewer-1',
    clubId: CLUB_ID,
    status: 'active',
    role: 'member',
    joinedAt: '2026-06-01T10:00:00Z',
    subscriptionExpiresAt: null,
    duesClaimedAt: null,
    duesClaimMethod: null,
    ...over,
  } as MembershipDto;
}

/**
 * Три источника: фид событий, членства (роль-развилка через useOrganizerClubs) и детали клуба
 * (обогащение организаторского клуб-пикера).
 */
function mockEndpoints(opts: {
  clubs: MembershipDto[];
  eventsResponder: Parameters<typeof http.get>[1];
}) {
  server.use(
    http.get('*/api/users/me/events', opts.eventsResponder),
    http.get('*/api/users/me/clubs', () => HttpResponse.json(opts.clubs)),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID, ownerId: 'viewer-1', name: 'Шахматы', description: 'd', category: 'board_games',
      accessType: 'open', city: 'Москва', district: null, memberLimit: 20, subscriptionPrice: 0,
      paymentLink: null, paymentMethodNote: null, avatarUrl: null, rules: null,
      applicationQuestion: null, inviteLink: null, memberCount: 5, isActive: true,
    })),
  );
}

function sectionHeaders(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.rd-section-sub-h')).map(
    (el) => el.textContent?.trim() ?? '',
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useCreateFlowStore.setState({ isOpen: false });
});

describe('EventsTab — секция «История» (Итерация 5)', () => {
  it('рендерит «История» последней секцией, после предстоящих (AC-H1)', async () => {
    mockEndpoints({
      clubs: [],
      eventsResponder: () => HttpResponse.json(feed([
        baseEvent({ id: 'up', title: 'Скоро встреча', eventDatetime: new Date(NOW + DAY).toISOString() }),
        historyEvent(),
      ])),
    });
    const { container } = renderWithProviders(<EventsTab />);

    expect(await screen.findByText('Эта неделя')).toBeInTheDocument();
    expect(await screen.findByText('История')).toBeInTheDocument();

    const headers = sectionHeaders(container);
    // «История» — последний заголовок секции.
    const lastHeader = headers[headers.length - 1] ?? '';
    expect(lastHeader).toMatch(/^История/);
    expect(lastHeader).not.toMatch(/^Эта неделя/);
  });

  it('сохраняет порядок истории с бэкенда, не пересортировывает по дате (AC-H1)', async () => {
    // Массив специально не по возрастанию дат: recent (позже) раньше old (раньше).
    const recent = historyEvent({ id: 'recent', title: 'Недавняя', eventDatetime: new Date(NOW - DAY).toISOString() });
    const old = historyEvent({ id: 'old', title: 'Давняя', eventDatetime: new Date(NOW - 30 * DAY).toISOString() });
    mockEndpoints({
      clubs: [],
      eventsResponder: () => HttpResponse.json(feed([recent, old])),
    });
    const { container } = renderWithProviders(<EventsTab />);

    expect(await screen.findByText('История')).toBeInTheDocument();
    // История рендерится компактной строкой HistoryCard (класс .rd-hist-title), не .rd-act-ttl.
    const titles = Array.from(container.querySelectorAll('.rd-hist-title')).map((el) => el.textContent);
    expect(titles).toEqual(['Недавняя', 'Давняя']);
  });

  it('пустые предстоящие + непустая история → сцена «Предстоящих событий нет» И секция «История», без тизера (AC-H11)', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'organizer' })],
      eventsResponder: () => HttpResponse.json(feed([historyEvent()])),
    });
    renderWithProviders(<EventsTab />);

    // Сцена лиса не спрятана историей, но её заголовок сигналит именно об отсутствии предстоящих.
    expect(await screen.findByText('Предстоящих событий нет')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Создать событие' })).toBeInTheDocument();
    expect(await screen.findByText('История')).toBeInTheDocument();
    expect(await screen.findByText('Прошлая встреча')).toBeInTheDocument();
    // Тизер «скоро здесь» при непустой истории не рендерится.
    expect(screen.queryByText('скоро здесь')).not.toBeInTheDocument();
  });

  it('участник: пустые предстоящие + непустая история → сцена «Предстоящих событий нет» с CTA «Перейти в Поиск» И «История» (AC-H11, member)', async () => {
    // Нет организаторских клубов → участническая ветка пустого состояния.
    mockEndpoints({
      clubs: [membership({ role: 'member' })],
      eventsResponder: () => HttpResponse.json(feed([historyEvent()])),
    });
    renderWithProviders(<EventsTab />);

    expect(await screen.findByText('Предстоящих событий нет')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Перейти в Поиск' })).toBeInTheDocument();
    // Организаторский CTA участнику не показывается.
    expect(screen.queryByRole('button', { name: 'Создать событие' })).not.toBeInTheDocument();
    expect(await screen.findByText('История')).toBeInTheDocument();
    expect(await screen.findByText('Прошлая встреча')).toBeInTheDocument();
    expect(screen.queryByText('скоро здесь')).not.toBeInTheDocument();
  });

  it('пустые предстоящие + пустая история → сцена «Событий пока нет» с тизером, без секции «История» (AC-H12)', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'organizer' })],
      eventsResponder: () => HttpResponse.json(EMPTY_FEED),
    });
    renderWithProviders(<EventsTab />);

    expect(await screen.findByText('Событий пока нет')).toBeInTheDocument();
    // Тизер «скоро здесь» присутствует, пока истории нет.
    expect(await screen.findByText('скоро здесь')).toBeInTheDocument();
    expect(screen.queryByText('История')).not.toBeInTheDocument();
    expect(screen.queryByText('Предстоящих событий нет')).not.toBeInTheDocument();
  });

  it('карточка истории компактная: title + клуб, без обложки/бейджа и без счётчика «идут», дата-плитка раздельно (AC-H13)', async () => {
    mockEndpoints({
      clubs: [],
      eventsResponder: () => HttpResponse.json(feed([
        historyEvent({ myParticipationStatus: 'confirmed', goingCount: 7, confirmedCount: 5, title: 'Прошедшая встреча' }),
      ])),
    });
    const { container } = renderWithProviders(<EventsTab />);

    // Название и клуб (подстрока «Шахматы · Кафе») присутствуют.
    expect(await screen.findByText('Прошедшая встреча')).toBeInTheDocument();
    expect(screen.getByText(/Шахматы/)).toBeInTheDocument();
    // Компактная строка: обложки полноразмерной карточки нет.
    expect(container.querySelector('.rd-act-cover')).toBeNull();
    // Бейджа у истории нет совсем (решение PO 2026-07-20): секция «История» и так означает
    // «ты был» — бейдж дублировал бы заголовок. «Подтверждён» (finalStatus=confirmed) тоже перебит.
    expect(screen.queryByText('Ты был')).not.toBeInTheDocument();
    expect(screen.queryByText('Подтверждён')).not.toBeInTheDocument();
    // Счётчик «N идут» (настоящее время) на прошедшем событии не показывается.
    expect(screen.queryByText(/идут/)).not.toBeInTheDocument();
    // Дата-плитка: число и месяц — раздельные непустые элементы.
    const day = container.querySelector('.rd-hist-day');
    const month = container.querySelector('.rd-hist-month');
    expect(day?.textContent?.trim()).toBeTruthy();
    expect(month?.textContent?.trim()).toBeTruthy();
    expect(day?.textContent).not.toEqual(month?.textContent);
  });

  it('isHistory — единственный признак: событие в status=stage_2 попадает в «Историю» (лаг крона, AC-H14)', async () => {
    mockEndpoints({
      clubs: [],
      eventsResponder: () => HttpResponse.json(feed([
        historyEvent({
          id: 'lagged',
          title: 'Отмечен пришедшим',
          status: 'stage_2',
          eventDatetime: new Date(NOW - 2 * 60 * 60 * 1000).toISOString(),
          isHistory: true,
        }),
      ])),
    });
    const { container } = renderWithProviders(<EventsTab />);

    expect(await screen.findByText('История')).toBeInTheDocument();
    // Событие не провалилось в «Эта неделя» несмотря на stage_2 и почти-настоящую дату.
    expect(screen.queryByText('Эта неделя')).not.toBeInTheDocument();
    expect(sectionHeaders(container).map((h) => h.replace(/\s+·.*$/, ''))).toEqual(['История']);
  });
});
