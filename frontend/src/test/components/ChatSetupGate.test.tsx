import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ChatLinkStatusDto } from '../../types/api';

/**
 * Окно «чат подключён» после возвращения из Telegram.
 *
 * Telegram добавляет бота в группу с выключенными ползунками прав, и без них функции чата
 * не включаются. Раньше об этом можно было узнать только в «Управлении → Чат», куда человек
 * после привязки не обязан заходить (жалоба PO 2026-08-15) — теперь окно ловит его само,
 * на любом экране.
 */

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  openTelegramLink: Object.assign(vi.fn(), { isAvailable: () => true }),
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ChatSetupGate } from '../../components/club/ChatSetupGate';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); vi.clearAllMocks(); localStorage.clear(); });
afterAll(() => server.close());

const CLUB_ID = 'club-1';
const PENDING_KEY = 'clubs:chat-linking-pending';
const START_URL = `https://t.me/clubs_test_bot?startgroup=${CLUB_ID}`;

function status(over: Partial<ChatLinkStatusDto> = {}): ChatLinkStatusDto {
  return {
    linked: true,
    chatTitle: 'Партия — чат',
    linkedAt: new Date().toISOString(),
    botStatus: 'administrator',
    canPinMessages: false,
    canInviteUsers: false,
    canRestrictMembers: false,
    canManageTags: false,
    clubLinkPinned: false,
    historyVisibleToNewMembers: true,
    doorEnabled: false,
    doorInviteLink: null,
    livePinEnabled: false,
    skladchinaStatusEnabled: false,
    strictModeEnabled: false,
    awardTagsEnabled: false,
    startGroupUrl: START_URL,
    ...over,
  };
}

function mockStatus(dto: ChatLinkStatusDto) {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(dto)),
    // Название клуба берётся из детальки — окно должно назвать, к какому клубу подключён чат
    http.get(`*/api/clubs/${CLUB_ID}`, () => HttpResponse.json({ id: CLUB_ID, name: 'Партия' })),
  );
}

/** Организатор ушёл привязывать чат `ageMs` назад и вернулся. */
function pendingLinking(ageMs = 0) {
  localStorage.setItem(
    PENDING_KEY,
    JSON.stringify({ clubId: CLUB_ID, startedAt: Date.now() - ageMs }),
  );
}

describe('ChatSetupGate — окно статуса после привязки чата', () => {
  it('вернулся после привязки — окно со списком прав и путём в настройки группы', async () => {
    pendingLinking();
    mockStatus(status());
    renderWithProviders(<ChatSetupGate />);

    // В заголовке — и чат, и клуб: у организатора клубов может быть несколько
    expect(
      await screen.findByText(/Чат «Партия — чат» подключён к клубу «Партия»/),
    ).toBeInTheDocument();
    expect(screen.getByText('Закрепление сообщений')).toBeInTheDocument();
    expect(screen.getByText('Приглашение участников')).toBeInTheDocument();
    expect(screen.getByText('Блокировка пользователей')).toBeInTheDocument();
    expect(screen.getByText('Управление тегами')).toBeInTheDocument();
    // Имя бота — из deep link: в списке админов группы искать надо конкретное имя
    expect(screen.getByText(/@clubs_test_bot/)).toBeInTheDocument();
  });

  it('права уже выданы — окно поздравляет и не зовёт в настройки', async () => {
    pendingLinking();
    mockStatus(status({
      canPinMessages: true,
      canInviteUsers: true,
      canRestrictMembers: true,
      canManageTags: true,
    }));
    renderWithProviders(<ChatSetupGate />);

    expect(await screen.findByText(/получил все права/)).toBeInTheDocument();
    expect(screen.queryByText(/Управление группой/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Проверить права' })).not.toBeInTheDocument();
  });

  it('никто ничего не привязывал — окна нет и статус не запрашивается', async () => {
    let asked = false;
    server.use(http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => {
      asked = true;
      return HttpResponse.json(status());
    }));
    renderWithProviders(<ChatSetupGate />);

    await waitFor(() => expect(screen.queryByText(/подключён/)).not.toBeInTheDocument());
    expect(asked).toBe(false);
  });

  it('ушёл привязывать, но передумал — чат не привязан, окна нет', async () => {
    pendingLinking();
    mockStatus(status({ linked: false, chatTitle: null, botStatus: null }));
    renderWithProviders(<ChatSetupGate />);

    await waitFor(() => expect(screen.queryByText(/подключён/)).not.toBeInTheDocument());
  });

  it('отметка старше часа протухла — окно не всплывает задним числом', async () => {
    pendingLinking(2 * 60 * 60 * 1000);
    mockStatus(status());
    renderWithProviders(<ChatSetupGate />);

    await waitFor(() => expect(screen.queryByText(/подключён/)).not.toBeInTheDocument());
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });

  it('закрытие окна снимает отметку — второй раз оно не появится', async () => {
    pendingLinking();
    mockStatus(status());
    const user = userEvent.setup();
    const first = renderWithProviders(<ChatSetupGate />);

    await user.click(await screen.findByRole('button', { name: 'Понятно' }));
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();

    first.unmount();
    renderWithProviders(<ChatSetupGate />);
    await waitFor(() => expect(screen.queryByText(/подключён/)).not.toBeInTheDocument());
  });

  it('«Проверить права» дёргает refresh и обновляет отметки в списке', async () => {
    pendingLinking();
    let current = status();
    let refreshed = false;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.post(`*/api/clubs/${CLUB_ID}/chat-link/refresh`, () => {
        refreshed = true;
        current = status({
          canPinMessages: true,
          canInviteUsers: true,
          canRestrictMembers: true,
          canManageTags: true,
        });
        return HttpResponse.json(current);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ChatSetupGate />);

    await user.click(await screen.findByRole('button', { name: 'Проверить права' }));

    await waitFor(() => expect(refreshed).toBe(true));
    await waitFor(() => expect(screen.getByText(/получил все права/)).toBeInTheDocument());
  });
});
