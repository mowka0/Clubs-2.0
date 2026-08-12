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

const state: { chatType: string | undefined; platform: string } = {
  chatType: undefined,
  platform: 'ios',
};

// vi.mock хойстится в начало файла — моки, попадающие в фабрику значением, создаём через vi.hoisted
const { openTelegramLinkMock } = vi.hoisted(() => ({
  openTelegramLinkMock: Object.assign(vi.fn(), { isAvailable: () => true }),
}));

vi.mock('@telegram-apps/sdk-react', () => ({
  init: vi.fn(),
  retrieveLaunchParams: () => ({
    tgWebAppPlatform: state.platform,
    tgWebAppData: state.chatType ? { chat_type: state.chatType } : {},
  }),
  openTelegramLink: openTelegramLinkMock,
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
  state.chatType = undefined;
  state.platform = 'ios';
  resetChatOriginForTests();
  vi.clearAllMocks();
});

/** Приложение открыто кнопкой из чата клуба `clubId` — так это выглядит для chatOrigin. */
function launchFromChatOf(clubId: string) {
  state.chatType = 'supergroup';
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

  it.each(['sender', 'private'])(
    'открыто из лички (%s): чат открывается ссылкой — там он другой экран',
    async (chatType) => {
      state.chatType = chatType;
      rememberClubShown(CLUB_A);
      renderPill(CLUB_A);

      await userEvent.click(screen.getByRole('button', { name: /В чат/ }));

      expect(openTelegramLinkMock).toHaveBeenCalledWith(INVITE_LINK);
      expect(screen.queryByText('Вы уже в этом чате')).not.toBeInTheDocument();
    },
  );

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
