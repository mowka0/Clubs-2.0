import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { MembershipDto, PageResponse, MySkladchinaListItemDto } from '../../types/api';

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

import { SkladchinasTab } from '../../components/activities/SkladchinasTab';
import { useCreateFlowStore } from '../../store/useCreateFlowStore';

const CLUB_ID = 'club-1';

/** Пустой фид складчин (query success, 0 сборов). */
const EMPTY_FEED: PageResponse<MySkladchinaListItemDto> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  page: 0,
  size: 20,
};

/** Закрытый сбор для секции «История» (status ≠ active). */
function closedSkladchina(): MySkladchinaListItemDto {
  return {
    id: 'sk-hist-1',
    title: 'Аренда зала (июнь)',
    clubId: CLUB_ID,
    clubName: 'Шахматы',
    clubAvatarUrl: null,
    template: 'custom',
    paymentMode: 'fixed_equal',
    totalGoalKopecks: 500000,
    collectedKopecks: 500000,
    participantCount: 5,
    paidCount: 5,
    deadline: '2026-06-15T18:00:00Z',
    status: 'closed_success',
    isOrganizerView: true,
    myStatus: null,
    actionRequired: false,
    affectsReputation: false,
  };
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
 * Мокаем три источника: фид складчин, членства (роль-развилка через useOrganizerClubs)
 * и детали клуба (обогащение клуб-пикера). skladchinasResponder — кастомный, чтобы
 * протестировать и success-пустоту, и ошибку сети.
 */
function mockEndpoints(opts: {
  clubs: MembershipDto[];
  skladchinasResponder: Parameters<typeof http.get>[1];
}) {
  server.use(
    http.get('*/api/users/me/skladchinas', opts.skladchinasResponder),
    http.get('*/api/users/me/clubs', () => HttpResponse.json(opts.clubs)),
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({
      id: CLUB_ID, ownerId: 'viewer-1', name: 'Шахматы', description: 'd', category: 'board_games',
      accessType: 'open', city: 'Москва', district: null, memberLimit: 20, subscriptionPrice: 0,
      paymentLink: null, paymentMethodNote: null, avatarUrl: null, rules: null,
      applicationQuestion: null, inviteLink: null, memberCount: 5, isActive: true,
    })),
  );
}

function renderTab() {
  const user = userEvent.setup();
  const result = renderWithProviders(<SkladchinasTab />);
  return { ...result, user };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  useCreateFlowStore.setState({ isOpen: false });
});

describe('SkladchinasTab — роль-развилка пустого состояния (W3-03)', () => {
  it('организатор без сборов видит «Сборов пока нет» и кнопку «Создать сбор»; тап открывает флоу создания', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'organizer' })],
      skladchinasResponder: () => HttpResponse.json(EMPTY_FEED),
    });
    const { user } = renderTab();

    expect(await screen.findByText('Сборов пока нет')).toBeInTheDocument();
    const createBtn = await screen.findByRole('button', { name: 'Создать сбор' });

    expect(useCreateFlowStore.getState().isOpen).toBe(false);
    await user.click(createBtn);
    expect(useCreateFlowStore.getState().isOpen).toBe(true);
  });

  it('участник без сборов видит текст «добавит тебя» без кнопки «Создать сбор» и без «Перейти в Поиск»', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'member' })],
      skladchinasResponder: () => HttpResponse.json(EMPTY_FEED),
    });
    renderTab();

    expect(await screen.findByText('Сборов пока нет')).toBeInTheDocument();
    expect(await screen.findByText(/добавит тебя/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Создать сбор' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Перейти в Поиск/ })).not.toBeInTheDocument();
  });

  it('участник вообще без клубов тоже видит участнический вариант без кнопок', async () => {
    mockEndpoints({
      clubs: [],
      skladchinasResponder: () => HttpResponse.json(EMPTY_FEED),
    });
    renderTab();

    expect(await screen.findByText('Сборов пока нет')).toBeInTheDocument();
    expect(await screen.findByText(/добавит тебя/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Создать сбор' })).not.toBeInTheDocument();
  });

  it('только закрытые сборы → сцена «Активных сборов нет» с CTA организатора + секция «История» под ней', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'organizer' })],
      skladchinasResponder: () => HttpResponse.json({
        ...EMPTY_FEED,
        content: [closedSkladchina()],
        totalElements: 1,
        totalPages: 1,
      }),
    });
    renderTab();

    // Закрытые сборы не прячут пустое состояние активных (решение PO 2026-07-20)
    expect(await screen.findByText('Активных сборов нет')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Создать сбор' })).toBeInTheDocument();
    expect(await screen.findByText('История')).toBeInTheDocument();
    expect(await screen.findByText('Аренда зала (июнь)')).toBeInTheDocument();
    // Карточка-тизер «скоро здесь» при непустой истории не рендерится
    expect(screen.queryByText('скоро здесь')).not.toBeInTheDocument();
  });

  it('ошибка загрузки → error-сцена «Не удалось загрузить сборы» с «Повторить», НЕ пустая заставка', async () => {
    mockEndpoints({
      clubs: [membership({ role: 'organizer' })],
      skladchinasResponder: () => new HttpResponse(null, { status: 500 }),
    });
    renderTab();

    expect(await screen.findByText('Не удалось загрузить сборы')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Повторить' })).toBeInTheDocument();
    // error ≠ empty: заголовок пустого состояния и кнопка создания не показываются
    expect(screen.queryByText('Сборов пока нет')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Создать сбор' })).not.toBeInTheDocument();
  });
});
