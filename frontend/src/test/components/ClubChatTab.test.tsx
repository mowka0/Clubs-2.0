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
// localStorage чистим между тестами: памятка о правах помнит «уже видел» именно там,
// иначе первый же тест погасил бы её для всех следующих.
afterEach(() => { server.resetHandlers(); vi.clearAllMocks(); localStorage.clear(); });
afterAll(() => server.close());

const CLUB_ID = 'club-1';
const START_URL = `https://t.me/clubs_test_bot?startgroup=${CLUB_ID}&admin=pin_messages+invite_users+restrict_members+manage_tags`;

function status(over: Partial<ChatLinkStatusDto> = {}): ChatLinkStatusDto {
  return {
    linked: false,
    chatTitle: null,
    linkedAt: null,
    botStatus: null,
    canPinMessages: false,
    canInviteUsers: false,
    clubLinkPinned: false,
    historyVisibleToNewMembers: true,
    doorEnabled: false,
    doorInviteLink: null,
    canRestrictMembers: false,
    canManageTags: false,
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
    // Намерение отмечается ПЕРЕД уходом в Telegram: без него бот, получив my_chat_member,
    // не поймёт, что чат надо привязать к этому клубу (club-chat-link.md).
    http.post('*/api/chat-link/intent', () => new HttpResponse(null, { status: 204 })),
  );
}

const linkedHealthy = (over: Partial<ChatLinkStatusDto> = {}) => status({
  linked: true,
  chatTitle: 'Партия — чат',
  linkedAt: new Date().toISOString(),
  botStatus: 'administrator',
  canPinMessages: true,
  canInviteUsers: true,
  canRestrictMembers: true,
  canManageTags: true,
  ...over,
});

describe('ClubChatTab', () => {
  it('состояние A: не привязан — CTA открывает startgroup deep link и ставит отметку ожидания', async () => {
    mockStatus(status());
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    const cta = await screen.findByRole('button', { name: 'Привязать чат' });
    await userEvent.click(cta);

    await waitFor(() => expect(openTelegramLinkMock).toHaveBeenCalledWith(START_URL));
    // Отсюда человек уходит в Telegram (на iOS приложение закрывается) — отметка переживает
    // выход и по возвращении открывает окно со статусом подключения бота (ChatSetupGate).
    expect(localStorage.getItem('clubs:chat-linking-pending')).toContain(CLUB_ID);
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
    expect(screen.getByRole('switch', { name: 'Теги наград' })).toBeEnabled();
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

  // ---- Ссылка выдачи прав для администратора группы ----
  //
  // Владельцем клуба становится тот, кто добавил бота, а он вполне может быть рядовым
  // участником чата: сам права не выдаст. Значит ссылку нужно уметь взять из управления и
  // отдать администратору (просьба PO 2026-08-19).

  it('бот в чате без прав — блок с выдачей прав и ссылкой для админа', async () => {
    mockStatus(status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'member' }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Боту не хватает прав администратора')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отправить ссылку админу' })).toBeInTheDocument();
  });

  it('«Отправить ссылку админу» открывает выбор чата, а не кладёт ссылку в буфер', async () => {
    mockStatus(status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'member' }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Отправить ссылку админу' }));

    // Индексом, а не .at(-1): у tsconfig фронта lib ниже es2022, и Array.prototype.at там нет.
    const calls = openTelegramLinkMock.mock.calls;
    const shared = calls[calls.length - 1][0] as string;
    expect(shared).toMatch(/^https:\/\/t\.me\/share\/url\?/);
    // Права разделены плюсами; URLSearchParams обязан отдать их как %2B, иначе на той стороне
    // список прочитается как пробелы и Telegram не запросит ни одного права.
    expect(shared).toContain(encodeURIComponent(START_URL));
    expect(shared).toContain('%2B');
    // Название группы в тексте: у админа их может быть несколько, а пикер Telegram покажет
    // ему весь список — «выберите эту группу» не отвечает на вопрос «какую именно».
    expect(decodeURIComponent(shared)).toContain('выберите группу «Партия — чат»');
  });

  it('«Выдать права» открывает ту же ссылку и помечает намерение как выдачу прав', async () => {
    let intent: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(
        status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'administrator' }),
      )),
      http.post('*/api/chat-link/intent', async ({ request }) => {
        intent = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Выдать права' }));

    // grantRightsOnly отличает выдачу прав от привязки: чат уже наш, заново привязывать нечего.
    await waitFor(() => expect(intent).toEqual({ clubId: CLUB_ID, grantRightsOnly: true }));
    expect(openTelegramLinkMock).toHaveBeenCalledWith(START_URL);
  });

  it('«Проверить права ещё раз» не задваивается — при нехватке прав она одна, наверху', async () => {
    mockStatus(status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'member' }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await screen.findByText('Боту не хватает прав администратора');
    expect(screen.getAllByRole('button', { name: 'Проверить права ещё раз' })).toHaveLength(1);
  });

  it('с полным набором прав «Проверить права ещё раз» остаётся на месте', async () => {
    mockStatus(linkedHealthy());
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('button', { name: 'Проверить права ещё раз' })).toBeInTheDocument();
  });

  it('все обязательные права выданы — блока нет', async () => {
    mockStatus(linkedHealthy());
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Партия — чат')).toBeInTheDocument();
    expect(screen.queryByText('Боту не хватает прав администратора')).not.toBeInTheDocument();
  });

  it('бот вне чата — блока нет, чинить надо повторной привязкой', async () => {
    mockStatus(status({ linked: true, chatTitle: 'Партия — чат', botStatus: 'kicked' }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('button', { name: 'Привязать бота заново' })).toBeInTheDocument();
    expect(screen.queryByText('Боту не хватает прав администратора')).not.toBeInTheDocument();
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
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Вход в чат через заявки' })).toHaveAttribute('aria-checked', 'true'));
  });

  // Сырую invite-ссылку в карточке не показываем (решение PO 2026-08-19). Сама ссылка живёт
  // как жила — по ней работают кнопка «В чат» у участников и приглашения в DM.
  it('сырой invite-ссылки в карточке нет — ни строки, ни кнопки «Копировать»', async () => {
    mockStatus({ ...linkedHealthy(), doorEnabled: false, doorInviteLink: 'https://t.me/+linked' });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Партия — чат')).toBeInTheDocument();
    expect(screen.queryByText('https://t.me/+linked')).not.toBeInTheDocument();
    expect(screen.queryByText(/Данная ссылка уже активна и работает/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Копировать' })).not.toBeInTheDocument();
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
      http.post('*/api/chat-link/intent', () => new HttpResponse(null, { status: 204 })),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот удалён из чата')).toBeInTheDocument();

    // Под алертом — быстрая повторная привязка тем же deep link'ом (реестр №5)
    await userEvent.click(screen.getByRole('button', { name: 'Привязать бота заново' }));
    await waitFor(() => expect(openTelegramLinkMock).toHaveBeenCalledWith(START_URL));

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

  it('включение тегов наград шлёт PATCH только с awardTagsEnabled', async () => {
    let current = linkedHealthy();
    let patched: unknown = null;
    server.use(
      http.get(`*/api/clubs/${CLUB_ID}/chat-link`, () => HttpResponse.json(current)),
      http.patch(`*/api/clubs/${CLUB_ID}/chat-link`, async ({ request }) => {
        patched = await request.json();
        current = { ...linkedHealthy(), awardTagsEnabled: true };
        return HttpResponse.json(current);
      }),
    );
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await userEvent.click(await screen.findByRole('switch', { name: 'Теги наград' }));

    await waitFor(() => expect(patched).toEqual({ awardTagsEnabled: true }));
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Теги наград' })).toHaveAttribute('aria-checked', 'true'));
  });

  it('без права управления тегами тумблер тегов задизейблен', async () => {
    mockStatus(status({
      linked: true,
      chatTitle: 'Партия — чат',
      botStatus: 'administrator',
      canPinMessages: true,
      canInviteUsers: true,
      canRestrictMembers: true,
      canManageTags: false,
    }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('switch', { name: 'Теги наград' })).toBeDisabled();
    expect(screen.getByText('✕ теги запрещены')).toBeInTheDocument();
  });

  it('теги включены, но право отняли — алерт деградации', async () => {
    mockStatus({ ...linkedHealthy(), awardTagsEnabled: true, canManageTags: false });
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Бот потерял право управлять тегами')).toBeInTheDocument();
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

  it('скрытая история чата — подсказка владельцу, как её включить', async () => {
    // Причина «новички не видят закреп встречи»: Telegram прячет от них всё до вступления.
    mockStatus(linkedHealthy({ historyVisibleToNewMembers: false }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByText('Новые участники не видят историю чата')).toBeInTheDocument();
    // Путь в настройках Telegram — как он называется в клиенте (уточнение PO 2026-08-15).
    expect(screen.getByText(/Управление группой → История чата для новых участников/)).toBeInTheDocument();
  });

  it('история видна — подсказки нет', async () => {
    mockStatus(linkedHealthy({ historyVisibleToNewMembers: true }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await screen.findByText('Партия — чат');
    expect(screen.queryByText('Новые участники не видят историю чата')).not.toBeInTheDocument();
  });

  it('ссылка на клуб не закреплена — кнопка «Закрепить» шлёт запрос', async () => {
    let called = false;
    mockStatus(linkedHealthy({ clubLinkPinned: false }));
    server.use(
      http.post(`*/api/clubs/${CLUB_ID}/chat-link/pin-club-link`, () => {
        called = true;
        return HttpResponse.json(linkedHealthy({ clubLinkPinned: true }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    await user.click(await screen.findByRole('button', { name: 'Закрепить' }));

    await waitFor(() => expect(called).toBe(true));
  });

  it('без права закрепа кнопку закрепить нельзя', async () => {
    mockStatus(linkedHealthy({ canPinMessages: false, clubLinkPinned: false }));
    renderWithProviders(<ClubChatTab clubId={CLUB_ID} />);

    expect(await screen.findByRole('button', { name: 'Закрепить' })).toBeDisabled();
  });

});
