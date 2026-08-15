import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * Пилюля «💬 В чат» ведёт себя по-разному в зависимости от того, откуда открыто приложение.
 *
 * Открытое кнопкой из чата клуба, оно лежит ПОВЕРХ этого чата — переходить некуда, и вместо
 * мигающего экрана человек получает подсказку «сверните приложение» (баг прода 2026-08-11).
 * Во всех остальных случаях кнопка уводит ссылкой, как раньше.
 */

const state: { startParam: string | undefined; platform: string } = {
  startParam: undefined,
  platform: 'ios',
};

// vi.mock хойстится в начало файла — моки, попадающие в фабрику значением, создаём через vi.hoisted
const { openTelegramLinkMock, closeMock } = vi.hoisted(() => ({
  openTelegramLinkMock: Object.assign(vi.fn(), { isAvailable: () => true }),
  closeMock: Object.assign(vi.fn(), { isAvailable: () => true }),
}));

vi.mock('@telegram-apps/sdk-react', () => ({
  init: vi.fn(),
  retrieveLaunchParams: () => ({
    tgWebAppPlatform: state.platform,
    tgWebAppStartParam: state.startParam,
  }),
  openTelegramLink: openTelegramLinkMock,
  miniApp: { close: closeMock },
  hapticFeedbackImpactOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackNotificationOccurred: Object.assign(vi.fn(), { isAvailable: () => false }),
  hapticFeedbackSelectionChanged: Object.assign(vi.fn(), { isAvailable: () => false }),
}));

import { ClubChatPill } from '../../components/club/ClubChatPill';
import { rememberClubShown, resetChatOriginForTests } from '../../telegram/chatOrigin';

const CLUB_A = 'club-a';
const CLUB_B = 'club-b';
const INVITE_LINK = 'https://t.me/+abcdef123456';

beforeEach(() => {
  state.startParam = undefined;
  state.platform = 'ios';
  resetChatOriginForTests();
  vi.clearAllMocks();
});

/**
 * Приложение открыто кнопкой из чата клуба `clubId`. Признак — payload `startapp`: такие
 * ссылки бот кладёт только в чат клуба. `chat_type` для этого не годится, Telegram отдаёт
 * по нему `private` и при запуске из группы (замер PO 2026-08-12).
 */
function launchFromChatOf(clubId: string, startParam = `club_${clubId}`) {
  state.startParam = startParam;
  rememberClubShown(clubId);
}

function renderPill(clubId: string) {
  return render(<ClubChatPill mode="open" clubId={clubId} inviteLink={INVITE_LINK} />);
}

describe('ClubChatPill — переход в чат клуба', () => {
  it('открыто из чата ЭТОГО клуба: подсказка «сверните приложение» вместо перехода', async () => {
    launchFromChatOf(CLUB_A);
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(screen.getByText('Вы уже в этом чате')).toBeInTheDocument();
    expect(openTelegramLinkMock).not.toHaveBeenCalled();
  });

  it('открыто из чата ДРУГОГО клуба: чат открывается ссылкой, как раньше', async () => {
    launchFromChatOf(CLUB_A);
    renderPill(CLUB_B);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
    expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
  });

  it('открыто из лички (menu button, startapp нет): чат открывается ссылкой', async () => {
    rememberClubShown(CLUB_A);
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
    expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
  });

  it('открыто по личному приглашению (invite_…): это личка, а не чат клуба', async () => {
    launchFromChatOf(CLUB_A, 'invite_a1b2c3d4e5f60718');
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
    expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
  });

  it.each(['event', 'skladchina'])(
    'заход по кнопке %s_… из чата клуба тоже распознаётся',
    async (prefix) => {
      // Кнопка «Проголосовать» в закрепе встречи ведёт на event_…, пост сбора — на skladchina_…;
      // клуб при этом узнаётся со страницы, которую deep link открыл (кейс PO 2026-08-12).
      launchFromChatOf(CLUB_A, `${prefix}_11111111-2222-3333-4444-555555555555`);
      renderPill(CLUB_A);

      await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

      expect(screen.getByText('Вы уже в этом чате')).toBeInTheDocument();
      expect(openTelegramLinkMock).not.toHaveBeenCalled();
    },
  );

  it('подсказка ничего не закрывает сама: свернуть приложение человек должен кнопкой Telegram', async () => {
    // Своя кнопка «выйти в чат» была и убрана по просьбе PO 2026-08-15: она закрывала Mini App
    // по-настоящему (свернуть кодом нельзя), а человек ждал сворачивания с сохранением состояния.
    launchFromChatOf(CLUB_A);
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(screen.getByText('Вы уже в этом чате')).toBeInTheDocument();
    expect(closeMock).not.toHaveBeenCalled();
  });

  it('на компьютере подсказки нет: Mini App там отдельное окно рядом с чатом', async () => {
    state.platform = 'tdesktop';
    launchFromChatOf(CLUB_A);
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
  });

  it('источник фиксируется по ПЕРВОМУ показанному клубу — дальше человек ходит сам', async () => {
    launchFromChatOf(CLUB_A);
    rememberClubShown(CLUB_B); // перешёл на другой клуб внутри приложения
    renderPill(CLUB_B);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

    expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
  });

  it('«Понятно» закрывает подсказку', async () => {
    launchFromChatOf(CLUB_A);
    renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Понятно' }));

    expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
  });

  it('тап мимо подсказки её закрывает', async () => {
    launchFromChatOf(CLUB_A);
    const { container } = renderPill(CLUB_A);

    await userEvent.click(screen.getByRole('button', { name: /В чат/ }));
    const veil = document.querySelector('.rd-mzhint-veil');
    expect(veil).not.toBeNull();
    await userEvent.click(veil as Element);

    expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
    expect(container).toBeTruthy();
  });
});
