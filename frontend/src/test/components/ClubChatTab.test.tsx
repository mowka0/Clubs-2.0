import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import type { ChatLinkStatusDto } from '../../types/api';

// vi.mock хойстится в начало файла — переменные для фабрики должны создаваться через vi.hoisted
const { openTelegramLinkMock } = vi.hoisted(() => ({
  openTelegramLinkMock: Object.assign(vi.fn(), { isAvailable: () => true }),
}));

vi.mock('@telegram-apps/sdk-react', () => ({
  retrieveLaunchParams: () => ({ initDataRaw: 'test' }),
  init: vi.fn(),
  openTelegramLink: openTelegramLinkMock,
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

vi.mock('@telegram-apps/telegram-ui', () => import('../mocks/telegramUi'));
vi.mock('../../telegram/sdk', () => ({
  initTelegramSdk: vi.fn(),
  getInitDataRaw: () => 'test-init-data',
}));

import { ClubChatTab } from '../../components/manage/ClubChatTab';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => { server.resetHandlers(); vi.clearAllMocks(); });
afterAll(() => server.close());

const CLUB_ID = 'club-1';
const START_URL = `https://t.me/clubs_test_bot?startgroup=${CLUB_ID}&admin=pin_messages+invite_users+restrict_members+promote_members`;

function status(over: Partial<ChatLinkStatusDto> = {}): ChatLinkStatusDto {
  return {
    linked: false,
    chatTitle: null,
    linkedAt: null,
    botStatus: null,
    canPinMessages: false,
    canInviteUsers: false,
    doorEnabled: false,
    doorInviteLink: null,
    canRestrictMembers: false,
    canPromoteMembers: false,
    livePinEnabled: false,
    skladchinaStatusEnabled: false,
    strictModeEnabled: false,
    awardTitlesEnabled: false,
    startGroupUrl: START_URL,
    ...over,
  };
}

function mockStatus(dto: ChatLinkStatusDto) {
  server.use(
    http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(dto)),
  );
}

const linkedHealthy = () => status({
  linked: true,
  chatTitle: 'Партия — чат',
  linkedAt: new Date().toISOString(),
  botStatus: 'administrator',
  canPinMessages: true,
  canInviteUsers: true,
  canRestrictMembers: true,
  canPromoteMembers: true,
});

describe('ClubChatTab', () => {
  it('состояние A: не привязан — CTA открывает startgroup deep link', async () => {
    mockStatus(status());
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    const cta = await screen.findByRole('button', { name: 'Привязать чат' });
    await userEvent.click(cta);

    expect(openTelegramLinkMock).toHaveBeenCalledWith(START_URL);
  });

  it('состояние B: привязан и здоров — карточка чата, зелёные пиллы, тумблер двери активен', async () => {
    mockStatus(linkedHealthy());
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Партия — чат')).toBeInTheDocument();
    expect(screen.getByText('✓ бот в чате')).toBeInTheDocument();
    expect(screen.getByText('✓ приглашения разрешены')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Вход в чат через заявки' })).toBeEnabled();
    // Живой закреп (слайс 3), статус сборов (слайс 3.5) и строгий режим (слайс 5) активны
    expect(screen.getByRole('switch', { name: 'Живой закреп' })).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Статус сборов в чате' })).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Строгий режим' })).toBeEnabled();
    expect(screen.getByRole('switch', { name: 'Титулы наград' })).toBeEnabled();
  });

  it('включение статуса сборов шлёт PATCH только с skladchinaStatusEnabled', async () => {
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), skladchinaStatusEnabled: true };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Статус сборов в чате' }));

    await waitFor(() => expect(patched).toEqual({ skladchinaStatusEnabled: true }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Статус сборов в чате' })).toHaveAttribute('aria-checked', 'true'));
  });

  it('статус сборов НЕ требует права закрепа (гейт — только бот в чате)', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: false,
      canInviteUsers: true,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Статус сборов в чате' })).toBeEnabled();
    // а живой закреп при этом задизейблен — права разные
    expect(screen.getByRole('switch', { name: 'Живой закреп' })).toBeDisabled();
  });

  it('бот кикнут — статус сборов включить нельзя', async () => {
    mockStatus(status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'kicked' }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Статус сборов в чате' })).toBeDisabled();
  });

  it('включение живого закрепа шлёт PATCH только с livePinEnabled', async () => {
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), livePinEnabled: true };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Живой закреп' }));

    await waitFor(() => expect(patched).toEqual({ livePinEnabled: true }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Живой закреп' })).toHaveAttribute('aria-checked', 'true'));
  });

  it('без права закрепа тумблер живого закрепа задизейблен', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: false,
      canInviteUsers: true,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Живой закреп' })).toBeDisabled();
    expect(screen.getByText('✕ закреп запрещён')).toBeInTheDocument();
  });

  it('живой закреп включён, но право закрепа отняли — алерт деградации', async () => {
    mockStatus({ ...linkedHealthy(), livePinEnabled: true, canPinMessages: false });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот потерял право закреплять сообщения')).toBeInTheDocument();
  });

  it('включение двери шлёт PATCH и показывает door-ссылку из ответа', async () => {
    // GET отдаёт «текущее серверное» состояние: мутация инвалидирует детальку клуба по
    // префиксу (chat-link — её ребёнок), и рефетч должен видеть уже включённую дверь.
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), doorEnabled: true, doorInviteLink: 'https://t.me/+door123' };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Вход в чат через заявки' }));

    await waitFor(() => expect(patched).toEqual({ doorEnabled: true }));
    expect(await screen.findByText('https://t.me/+door123')).toBeInTheDocument();
  });

  it('invite-ссылка видна и при ВЫКЛЮЧЕННОЙ двери (создаётся при привязке, реестр №4)', async () => {
    mockStatus({ ...linkedHealthy(), doorEnabled: false, doorInviteLink: 'https://t.me/+linked' });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('https://t.me/+linked')).toBeInTheDocument();
    expect(screen.getByText(/Данная ссылка уже активна и работает/)).toBeInTheDocument();
  });

  it('без права приглашать тумблер двери задизейблен', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: true,
      canInviteUsers: false,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Вход в чат через заявки' })).toBeDisabled();
    expect(screen.getByText('✕ приглашения запрещены')).toBeInTheDocument();
  });

  it('состояние C: бот кикнут — алерт и «Проверить права ещё раз» дергает refresh', async () => {
    let current = status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'kicked',
    });
    let refreshed = false;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.post(`*/api/clubs/${CLUB_ID}/chat-link/refresh`, () => {
        refreshed = true;
        current = linkedHealthy();
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот удалён из чата')).toBeInTheDocument();

    // Под алертом — быстрая повторная привязка тем же deep link'ом (реестр №5)
    await userEvent.click(screen.getByRole('button', { name: 'Привязать бота заново' }));
    expect(openTelegramLinkMock).toHaveBeenCalledWith(START_URL);

    await userEvent.click(screen.getByRole('button', { name: 'Проверить права ещё раз' }));

    await waitFor(() => expect(refreshed).toBe(true));
    // После refresh пришло здоровое состояние — алерт исчез
    await waitFor(() => expect(screen.queryByText('Бот удалён из чата')).not.toBeInTheDocument());
  });

  it('включение строгого режима шлёт PATCH только с strictModeEnabled', async () => {
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), strictModeEnabled: true };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Строгий режим' }));

    await waitFor(() => expect(patched).toEqual({ strictModeEnabled: true }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Строгий режим' })).toHaveAttribute('aria-checked', 'true'));
  });

  it('без права блокировки тумблер строгого режима задизейблен', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: true,
      canInviteUsers: true,
      canRestrictMembers: false,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Строгий режим' })).toBeDisabled();
    expect(screen.getByText('✕ блокировки запрещены')).toBeInTheDocument();
  });

  it('строгий режим включён, но право блокировки отняли — алерт деградации', async () => {
    mockStatus({ ...linkedHealthy(), strictModeEnabled: true, canRestrictMembers: false });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот потерял право блокировать участников')).toBeInTheDocument();
  });

  it('включение титулов наград шлёт PATCH только с awardTitlesEnabled', async () => {
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), awardTitlesEnabled: true };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Титулы наград' }));

    await waitFor(() => expect(patched).toEqual({ awardTitlesEnabled: true }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Титулы наград' })).toHaveAttribute('aria-checked', 'true'));
  });

  it('без права назначения админов тумблер титулов задизейблен', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: true,
      canInviteUsers: true,
      canRestrictMembers: true,
      canPromoteMembers: false,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Титулы наград' })).toBeDisabled();
    expect(screen.getByText('✕ назначение админов запрещено')).toBeInTheDocument();
  });

  it('титулы включены, но право отняли — алерт деградации', async () => {
    mockStatus({ ...linkedHealthy(), awardTitlesEnabled: true, canPromoteMembers: false });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот потерял право назначать администраторов')).toBeInTheDocument();
  });

  it('отвязка: подтверждение в модалке шлёт DELETE', async () => {
    mockStatus(linkedHealthy());
    let deleted = false;
    server.use(
      http.delete(`*/api/clubs/${CLUB_ID}/chat-link`, () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Отвязать чат' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Отвязать' }));

    await waitFor(() => expect(deleted).toBe(true));
  });
});
