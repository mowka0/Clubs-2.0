import { openTelegramLink } from '@telegram-apps/sdk-react';
import { closeMiniApp, isLaunchedFromGroupChat } from '../telegram/sdk';

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

/**
 * Уводит пользователя в чат клуба.
 *
 * От [openTmeLink] отличается одним: если приложение открыто кнопкой ИЗ группового чата,
 * после перехода мы закрываемся. Telegram в этом случае держит чат-источник открытым прямо
 * под Mini App, и просьба «открой этот чат» для него бессмысленна — переходить некуда, экран
 * мигает и остаётся приложение (баг прода 2026-08-11: из лички с ботом кнопка работала,
 * из чата клуба — нет). Закрытие возвращает человека ровно туда, откуда он пришёл, — в чат.
 *
 * Ссылку всё равно открываем первой и всегда: из чата клуба А можно смотреть клуб Б, и тогда
 * чат Б — другой экран, Telegram на него переключится, а закрытие лишь уберёт Mini App
 * с дороги. Цена — приложение в этом случае не остаётся свёрнутым; из лички поведение
 * не меняется вовсе.
 */
export function openChatLink(url: string): void {
  openTmeLink(url);
  if (isLaunchedFromGroupChat()) closeMiniApp();
}
