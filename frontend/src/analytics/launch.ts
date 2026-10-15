import { getStartParam } from '../telegram/sdk';
import { platformName } from '../telegram/platform';
import { deepLinkKind } from '../telegram/deepLink';
import { track } from './track';

let opened = false;

/**
 * `app_opened` — один раз за запуск, после успешной авторизации (product-analytics.md § 3.2):
 * откуда пришли (вид `startapp`-параметра) и на какой платформе. Повторные вызовы при
 * ре-рендерах Layout ничего не пишут.
 */
export function trackAppOpened(): void {
  if (opened) return;
  opened = true;
  track('app_opened', { entry: deepLinkKind(getStartParam()), platform: platformName() });
}

export function resetLaunchForTests(): void {
  opened = false;
}
