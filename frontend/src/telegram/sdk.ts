import {
  init,
  retrieveLaunchParams,
  initData,
  viewport,
  swipeBehavior,
  shareMessage,
} from '@telegram-apps/sdk-react';

let initialized = false;

export function initTelegramSdk(): void {
  if (initialized) return;
  try {
    init({ acceptCustomStyles: true });
    initData.restore(); // Обязательно в v3, чтобы заполнить initDataRaw
    setupViewport();
    setupSwipeBehavior();
    initialized = true;
  } catch (_e) {
    // Не в среде Telegram — mock-режим
    initialized = true;
  }
}

/**
 * Монтирует viewport и разворачивает Mini App на всю доступную высоту.
 *
 * `viewport.mount()` асинхронен (резолвится, когда хост сообщит свою геометрию);
 * expand выполняется после промиса mount, чтобы гарантировать, что хост его принял.
 * Каждый вызов защищён `.isAvailable()` и изолирован в своём catch, чтобы сбой здесь
 * никогда не прерывал инициализацию SDK и не ломал fallback вне Telegram (локальная разработка).
 *
 * Примечание: есть `requestFullscreen()` для более агрессивного режима, перекрывающего
 * шапку Telegram — намеренно не используется здесь; нужна только полная высота плюс
 * отключённое сворачивание свайпом.
 */
function setupViewport(): void {
  try {
    if (!viewport.isMounted() && viewport.mount.isAvailable()) {
      viewport
        .mount()
        .then(() => {
          if (viewport.expand.isAvailable()) viewport.expand();
          // Биндим CSS-переменные Telegram (--tg-viewport-stable-height и др.). Без этого
          // нижние шиты завязаны на `vh`, который на iOS переоценивает высоту (не учитывает
          // динамические панели/шторку) — низ модалки уходит под фолд, и до секции роли не
          // доскроллить без ручного растягивания. Переменная = реальная видимая высота хоста.
          if (viewport.bindCssVars.isAvailable()) viewport.bindCssVars();
        })
        .catch(() => {
          // Mount отклонён (хост без поддержки viewport) — оставляем высоту по умолчанию
        });
    } else if (viewport.expand.isAvailable()) {
      viewport.expand();
      if (viewport.bindCssVars.isAvailable()) viewport.bindCssVars();
    }
  } catch (_e) {
    // Viewport API недоступен — пропускаем
  }
}

/**
 * Монтирует компонент swipe-behavior и отключает вертикальные свайпы, чтобы прокрутка
 * содержимого страницы больше не сворачивала/минимизировала Mini App. Приложение всё ещё
 * можно свернуть, потянув за шапку Telegram.
 *
 * `swipeBehavior.mount()` синхронен в v3; `disableVertical` требует, чтобы компонент был
 * смонтирован, поэтому выполняется сразу после. Защищён и изолирован так же, как viewport.
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
    // Swipe behavior не поддерживается (Mini Apps < 7.7) — прокрутка остаётся стандартной
  }
}

/**
 * Возвращает deep-link параметр startapp (tgWebAppStartParam из Telegram), если юзер
 * открыл Mini App по ссылке t.me/bot/app?startapp=<value>.
 * Используется DeepLinkHandler для перехода, например, на /skladchina/<id>.
 */
export function getStartParam(): string | null {
  try {
    const params = retrieveLaunchParams();
    const raw = (params as unknown as { startParam?: string; tgWebAppStartParam?: string }).startParam
      ?? (params as unknown as { tgWebAppStartParam?: string }).tgWebAppStartParam;
    if (raw && typeof raw === 'string' && raw.length > 0) return raw;
  } catch (_e) {
    // Не в среде Telegram
  }
  const fromNative = (window as unknown as { Telegram?: { WebApp?: { initDataUnsafe?: { start_param?: string } } } })
    ?.Telegram?.WebApp?.initDataUnsafe?.start_param;
  if (fromNative) return fromNative;
  return null;
}

/**
 * Доступен ли нативный шаринг prepared message (Mini Apps 8.0+). false — старый клиент
 * или не-Telegram среда: боттом-шит приглашения прячет «Отправить в Telegram»
 * и оставляет только «Скопировать ссылку».
 */
export function canShareMessage(): boolean {
  try {
    return shareMessage.isAvailable();
  } catch (_e) {
    return false;
  }
}

/**
 * Открывает нативный пикер чатов с карточкой-приглашением (club-invites): сообщение,
 * заранее собранное ботом (savePreparedInlineMessage), уходит ОТ ИМЕНИ пользователя.
 * Бросает исключение при отказе/сбое — вызывающий показывает ошибку в шите.
 */
export async function shareInviteMessage(preparedMessageId: string): Promise<void> {
  await shareMessage(preparedMessageId);
}

export function getInitDataRaw(): string {
  // v3: initData.raw() — это signal, вызывать как функцию, чтобы получить текущее значение
  try {
    const raw = initData.raw();
    if (raw) return raw;
  } catch (_e) {
    // Не в среде Telegram
  }

  // Фолбэк: retrieveLaunchParams
  try {
    const params = retrieveLaunchParams();
    const raw = params.initDataRaw as string | undefined;
    if (raw) return raw;
  } catch (_e) {
    // Не в среде Telegram
  }

  // Фолбэк на нативный Telegram WebApp API
  const tgInitData = (window as unknown as { Telegram?: { WebApp?: { initData?: string } } })
    ?.Telegram?.WebApp?.initData;
  if (tgInitData) return tgInitData;

  const mock = import.meta.env.VITE_MOCK_INIT_DATA as string | undefined;
  if (mock) return mock;

  throw new Error('No initData available. Set VITE_MOCK_INIT_DATA in .env.development for local testing.');
}
