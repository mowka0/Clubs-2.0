import { retrieveLaunchParams } from '@telegram-apps/sdk-react';

/**
 * Платформы, которые считаются телефоном. Список — whitelist, а не blacklist десктопов:
 * незнакомая платформа получает обычное, «компьютерное» поведение, и это безопаснее.
 *
 * От этого зависят решения, осмысленные только на телефоне: полноэкранный режим (`sdk.ts`)
 * и подсказка «сверните приложение» (`chatOrigin.ts`).
 */
const PHONE_PLATFORMS = new Set(['android', 'android_x', 'ios']);

/** Телефон — да, компьютер и незнакомая платформа — нет. Вне Telegram — тоже нет. */
export function isPhonePlatform(): boolean {
  try {
    const { tgWebAppPlatform } = retrieveLaunchParams();
    return PHONE_PLATFORMS.has(tgWebAppPlatform);
  } catch (_e) {
    return false;
  }
}
