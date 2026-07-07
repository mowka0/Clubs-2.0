import { openTelegramLink } from '@telegram-apps/sdk-react';

/**
 * Открывает t.me-ссылку (deep link бота, invite link чата) внутри Telegram.
 * openTelegramLink переключает Telegram на нужный экран без закрытия Mini App-контекста;
 * вне Telegram (локальная разработка) — обычная новая вкладка.
 */
export function openTmeLink(url: string): void {
  try {
    if (openTelegramLink.isAvailable()) {
      openTelegramLink(url);
      return;
    }
  } catch (_e) {
    // Не в среде Telegram — падаем на window.open
  }
  window.open(url, '_blank', 'noopener');
}
