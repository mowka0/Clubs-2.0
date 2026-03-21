import { init, retrieveLaunchParams } from '@telegram-apps/sdk-react';

let initialized = false;

export function initTelegramSdk(): void {
  if (initialized) return;
  try {
    init({ acceptCustomStyles: true });
    initialized = true;
  } catch (_e) {
    // Not running in Telegram environment — mock mode
    initialized = true;
  }
}

export function getInitDataRaw(): string {
  try {
    const params = retrieveLaunchParams();
    const raw = params.initDataRaw as string | undefined;
    if (raw) return raw;
  } catch (_e) {
    // Not in Telegram environment
  }

  const mock = import.meta.env.VITE_MOCK_INIT_DATA as string | undefined;
  if (mock) return mock;

  throw new Error('No initData available. Set VITE_MOCK_INIT_DATA in .env.development for local testing.');
}
