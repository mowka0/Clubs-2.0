import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Переход в чат клуба зависит от того, ОТКУДА открыто приложение.
 *
 * Из лички с ботом Telegram честно переключает экран на чат. Открытое кнопкой из группы
 * приложение лежит поверх этого же чата, и переходить Telegram некуда — приложение должно
 * закрыться само, иначе человек остаётся там же (баг прода 2026-08-11).
 */

const state: { chatType: string | undefined } = { chatType: undefined };

// vi.mock хойстится в начало файла — моки, попадающие в фабрику значением, создаём через vi.hoisted
const { openTelegramLinkMock, closeMock } = vi.hoisted(() => ({
  openTelegramLinkMock: Object.assign(vi.fn(), { isAvailable: () => true }),
  closeMock: Object.assign(vi.fn(), { isAvailable: () => true }),
}));

vi.mock('@telegram-apps/sdk-react', () => ({
  init: vi.fn(),
  initData: { restore: vi.fn(), raw: () => 'raw' },
  retrieveLaunchParams: () => ({
    tgWebAppPlatform: 'ios',
    tgWebAppData: state.chatType ? { chat_type: state.chatType } : {},
  }),
  openTelegramLink: openTelegramLinkMock,
  miniApp: { close: closeMock },
  shareMessage: Object.assign(vi.fn(), { isAvailable: () => true }),
  swipeBehavior: {
    mount: Object.assign(vi.fn(), { isAvailable: () => true }),
    disableVertical: Object.assign(vi.fn(), { isAvailable: () => true }),
  },
  viewport: {
    isMounted: () => true,
    mount: Object.assign(vi.fn(), { isAvailable: () => true }),
    expand: Object.assign(vi.fn(), { isAvailable: () => true }),
    bindCssVars: Object.assign(vi.fn(), { isAvailable: () => true }),
    isCssVarsBound: () => true,
    isFullscreen: () => false,
    requestFullscreen: Object.assign(vi.fn(async () => {}), { isAvailable: () => true }),
    exitFullscreen: Object.assign(vi.fn(async () => {}), { isAvailable: () => true }),
  },
}));

import { openChatLink, openTmeLink } from '../../utils/telegramLinks';

const CHAT_URL = 'https://t.me/+abcdef123456';

beforeEach(() => {
  state.chatType = undefined;
  vi.clearAllMocks();
  delete (window as unknown as { Telegram?: unknown }).Telegram;
});

describe('openChatLink — переход в чат клуба', () => {
  it.each(['group', 'supergroup'])(
    'открыто из группового чата (%s) — открывает ссылку и закрывает приложение',
    (chatType) => {
      state.chatType = chatType;
      openChatLink(CHAT_URL);
      expect(openTelegramLinkMock).toHaveBeenCalledWith(CHAT_URL);
      expect(closeMock).toHaveBeenCalledOnce();
    },
  );

  it('ссылка открывается ДО закрытия — иначе чужой чат (клуб Б) не успеет открыться', () => {
    state.chatType = 'supergroup';
    openChatLink(CHAT_URL);
    expect(openTelegramLinkMock.mock.invocationCallOrder[0])
      .toBeLessThan(closeMock.mock.invocationCallOrder[0] as number);
  });

  it.each(['sender', 'private'])(
    'открыто из лички (%s) — приложение остаётся, Telegram сам переключит экран',
    (chatType) => {
      state.chatType = chatType;
      openChatLink(CHAT_URL);
      expect(openTelegramLinkMock).toHaveBeenCalledWith(CHAT_URL);
      expect(closeMock).not.toHaveBeenCalled();
    },
  );

  it('запуск без чата-источника (menu button, прямая ссылка) — приложение остаётся', () => {
    openChatLink(CHAT_URL);
    expect(openTelegramLinkMock).toHaveBeenCalledWith(CHAT_URL);
    expect(closeMock).not.toHaveBeenCalled();
  });

  it('тип чата берётся из нативного initDataUnsafe, если launch-параметров нет', () => {
    (window as unknown as { Telegram: { WebApp: { initDataUnsafe: { chat_type: string } } } })
      .Telegram = { WebApp: { initDataUnsafe: { chat_type: 'supergroup' } } };
    openChatLink(CHAT_URL);
    expect(closeMock).toHaveBeenCalledOnce();
  });
});

describe('openTmeLink — остальные t.me-ссылки', () => {
  it('приложение не закрывает даже из группы: пикер чатов «привязать бота» открывается поверх', () => {
    state.chatType = 'supergroup';
    openTmeLink('https://t.me/clubs_bot?startgroup=club-1');
    expect(openTelegramLinkMock).toHaveBeenCalledOnce();
    expect(closeMock).not.toHaveBeenCalled();
  });
});
