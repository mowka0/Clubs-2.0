import { init, retrieveLaunchParams, initData } from '@telegram-apps/sdk-react';

let initialized = false;

export function initTelegramSdk(): void {
  if (initialized) return;
  try {
    init({ acceptCustomStyles: true });
    initData.restore(); // Required in v3 to populate initDataRaw
    initialized = true;
  } catch (_e) {
    // Not running in Telegram environment — mock mode
    initialized = true;
  }
}

export function getInitDataRaw(): string {
  // v3: initData.raw() is a signal, call as function to get current value
  try {
    const raw = initData.raw();
    if (raw) return raw;
  } catch (_e) {
    // Not in Telegram environment
  }

  // Fallback: retrieveLaunchParams
  try {
    const params = retrieveLaunchParams();
    const raw = params.initDataRaw as string | undefined;
    if (raw) return raw;
  } catch (_e) {
    // Not in Telegram environment
  }

  // Fallback to native Telegram WebApp API
  const tgInitData = (window as unknown as { Telegram?: { WebApp?: { initData?: string } } })
    ?.Telegram?.WebApp?.initData;
  if (tgInitData) return tgInitData;

  const mock = import.meta.env.VITE_MOCK_INIT_DATA as string | undefined;
  if (mock) return mock;

  throw new Error('No initData available. Set VITE_MOCK_INIT_DATA in .env.development for local testing.');
}
