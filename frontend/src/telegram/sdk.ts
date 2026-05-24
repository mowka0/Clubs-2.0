import {
  init,
  retrieveLaunchParams,
  initData,
  viewport,
  swipeBehavior,
} from '@telegram-apps/sdk-react';

let initialized = false;

export function initTelegramSdk(): void {
  if (initialized) return;
  try {
    init({ acceptCustomStyles: true });
    initData.restore(); // Required in v3 to populate initDataRaw
    setupViewport();
    setupSwipeBehavior();
    initialized = true;
  } catch (_e) {
    // Not running in Telegram environment — mock mode
    initialized = true;
  }
}

/**
 * Mounts the viewport and expands the Mini App to full available height.
 *
 * `viewport.mount()` is async (resolves once the host reports its geometry);
 * expand runs after the mount promise to ensure the host accepts it. Each call
 * is guarded by `.isAvailable()` and isolated in its own catch so a failure here
 * never aborts SDK init or breaks the non-Telegram (local dev) fallback.
 *
 * Note: `requestFullscreen()` exists for a more aggressive mode that covers the
 * Telegram header — intentionally not used here; we only want full height plus
 * swipe-to-minimize disabled.
 */
function setupViewport(): void {
  try {
    if (!viewport.isMounted() && viewport.mount.isAvailable()) {
      viewport
        .mount()
        .then(() => {
          if (viewport.expand.isAvailable()) viewport.expand();
        })
        .catch(() => {
          // Mount rejected (host without viewport support) — leave default height
        });
    } else if (viewport.expand.isAvailable()) {
      viewport.expand();
    }
  } catch (_e) {
    // Viewport API unavailable — skip
  }
}

/**
 * Mounts the swipe-behavior component and disables vertical swipes so that
 * scrolling page content no longer collapses/minimizes the Mini App. The app
 * can still be minimized by dragging the Telegram header.
 *
 * `swipeBehavior.mount()` is synchronous in v3; `disableVertical` requires the
 * component mounted, so it runs right after. Guarded and isolated like viewport.
 */
function setupSwipeBehavior(): void {
  try {
    if (swipeBehavior.mount.isAvailable()) {
      swipeBehavior.mount();
    }
    if (swipeBehavior.disableVertical.isAvailable()) {
      swipeBehavior.disableVertical();
    }
  } catch (_e) {
    // Swipe behavior unsupported (Mini Apps < 7.7) — scrolling stays default
  }
}

/**
 * Returns deep-link startapp parameter (Telegram's tgWebAppStartParam) if user
 * opened the Mini App through a t.me/bot/app?startapp=<value> link.
 * Used by DeepLinkHandler to navigate to e.g. /skladchina/<id>.
 */
export function getStartParam(): string | null {
  try {
    const params = retrieveLaunchParams();
    const raw = (params as unknown as { startParam?: string; tgWebAppStartParam?: string }).startParam
      ?? (params as unknown as { tgWebAppStartParam?: string }).tgWebAppStartParam;
    if (raw && typeof raw === 'string' && raw.length > 0) return raw;
  } catch (_e) {
    // Not in Telegram environment
  }
  const fromNative = (window as unknown as { Telegram?: { WebApp?: { initDataUnsafe?: { start_param?: string } } } })
    ?.Telegram?.WebApp?.initDataUnsafe?.start_param;
  if (fromNative) return fromNative;
  return null;
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
