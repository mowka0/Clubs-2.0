import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * Полноэкранный режим включается только на телефонах. На компьютере Mini App должен
 * открываться обычным окном, причём ЯВНО выходя из fullscreen, если Telegram открыл нас
 * в нём по прямой ссылке (просьба PO 2026-07-31).
 */

const state = {
  platform: 'android',
  isFullscreen: false,
};

const requestFullscreen = Object.assign(vi.fn(async () => {}), { isAvailable: () => true });
const exitFullscreen = Object.assign(vi.fn(async () => {}), { isAvailable: () => true });

vi.mock('@telegram-apps/sdk-react', () => ({
  init: vi.fn(),
  initData: { restore: vi.fn(), raw: () => 'raw' },
  retrieveLaunchParams: () => ({ tgWebAppPlatform: state.platform }),
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
    isFullscreen: () => state.isFullscreen,
    requestFullscreen,
    exitFullscreen,
  },
}));

/** Каждый прогон стартует с чистого модуля: initTelegramSdk одноразовый по флагу. */
async function initSdk(platform: string, isFullscreen: boolean) {
  state.platform = platform;
  state.isFullscreen = isFullscreen;
  vi.resetModules();
  requestFullscreen.mockClear();
  exitFullscreen.mockClear();
  const { initTelegramSdk } = await import('../../telegram/sdk');
  initTelegramSdk();
}

beforeEach(() => {
  requestFullscreen.mockClear();
  exitFullscreen.mockClear();
});

describe('полноэкранный режим по платформе', () => {
  it.each(['android', 'android_x', 'ios'])('на телефоне (%s) запрашивает fullscreen', async (platform) => {
    await initSdk(platform, false);
    expect(requestFullscreen).toHaveBeenCalledOnce();
    expect(exitFullscreen).not.toHaveBeenCalled();
  });

  it.each(['tdesktop', 'macos', 'weba', 'webk', 'web'])(
    'на компьютере (%s) fullscreen не запрашивается',
    async (platform) => {
      await initSdk(platform, false);
      expect(requestFullscreen).not.toHaveBeenCalled();
    },
  );

  it('на компьютере ВЫХОДИТ из fullscreen, если Telegram открыл нас в нём', async () => {
    // Прямая ссылка на Mini App приходит с флагом tgWebAppFullscreen — на десктопе это
    // растягивало приложение на весь монитор.
    await initSdk('tdesktop', true);
    expect(exitFullscreen).toHaveBeenCalledOnce();
    expect(requestFullscreen).not.toHaveBeenCalled();
  });

  it('на телефоне, уже открытом в fullscreen, повторно не запрашивает', async () => {
    await initSdk('ios', true);
    expect(requestFullscreen).not.toHaveBeenCalled();
    expect(exitFullscreen).not.toHaveBeenCalled();
  });

  it('незнакомая платформа считается десктопом — во весь экран не разворачиваемся', async () => {
    await initSdk('some_new_client', false);
    expect(requestFullscreen).not.toHaveBeenCalled();
  });
});
