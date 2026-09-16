/**
 * Адреса, у которых есть смысл вне Telegram: они рендерятся роутером как обычный веб.
 * Всё остальное — приложение, и без initData оно не стартует.
 */
export const PUBLIC_WEB_PATHS = ['/about', '/pay/return', '/pay/fail'];

/**
 * Показать публичный лендинг вместо приложения: пришли не из Telegram и не на публичный адрес.
 * Модератор платёжного провайдера и человек по ссылке из рекламы открывают корень домена —
 * им нужна страница с описанием, ценой и офертой, а не белый экран требования initData.
 */
export function shouldShowLanding(pathname: string, insideTelegram: boolean): boolean {
  if (insideTelegram) return false;
  return !PUBLIC_WEB_PATHS.includes(pathname.replace(/\/+$/, '') || '/');
}
