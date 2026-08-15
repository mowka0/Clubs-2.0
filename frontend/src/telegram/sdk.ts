import {
  init,
  retrieveLaunchParams,
  initData,
  miniApp,
  viewport,
  swipeBehavior,
  shareMessage,
} from '@telegram-apps/sdk-react';
import { isPhonePlatform } from './platform';

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
 * Монтирует viewport, разворачивает Mini App на всю доступную высоту, включает
 * полноэкранный режим и публикует геометрию хоста в CSS-переменные.
 *
 * `viewport.mount()` асинхронен (резолвится, когда хост сообщит свою геометрию);
 * всё остальное выполняется после промиса mount, чтобы гарантировать, что хост это принял.
 * Каждый вызов защищён `.isAvailable()` и изолирован в своём catch, чтобы сбой здесь никогда
 * не прерывал инициализацию SDK и не ломал fallback вне Telegram (локальная разработка).
 */
function setupViewport(): void {
  try {
    if (!viewport.isMounted() && viewport.mount.isAvailable()) {
      viewport
        .mount()
        .then(() => {
          if (viewport.expand.isAvailable()) viewport.expand();
          bindViewportCssVars();
          applyFullscreenPolicy();
        })
        .catch(() => {
          // Mount отклонён (хост без поддержки viewport) — оставляем высоту по умолчанию
        });
    } else {
      if (viewport.expand.isAvailable()) viewport.expand();
      bindViewportCssVars();
      applyFullscreenPolicy();
    }
  } catch (_e) {
    // Viewport API недоступен — пропускаем
  }
}

/**
 * Приводит полноэкранный режим к тому, каким он должен быть на текущей платформе.
 *
 * Зачем вообще: стартовый режим решает НЕ приложение, а точка входа — Telegram присылает его
 * в launch-параметре `tgWebAppFullscreen`, из которого `viewport.mount()` инициализирует
 * `isFullscreen`. Прямая ссылка на Mini App (наши приглашения в клуб) приходила с
 * полноэкранным флагом, а menu-button у бота — без него, и одно и то же приложение
 * открывалось по-разному (баг PO 2026-07-29). Поэтому режим задаём сами — тогда он одинаков
 * из любой точки входа и не зависит от настроек конкретной ссылки на стороне Telegram.
 *
 * На телефоне это fullscreen, на компьютере — обычное окно, и выйти из режима нужно ЯВНО:
 * иначе прямая ссылка растянет Mini App на весь монитор, а menu-button — нет, и получится
 * та же непредсказуемость, только на десктопе.
 *
 * Приведение одноразовое, на старте: если пользователь потом сам сменит режим кнопкой
 * Telegram, мы его обратно не переключаем.
 *
 * На телефоне это fullscreen (узкий экран, шапка Telegram съедает заметную долю высоты),
 * на компьютере — обычное окно: там окно Mini App и так компактное, а полноэкранный режим
 * растянул бы его на весь монитор (просьба PO 2026-07-31).
 */
function applyFullscreenPolicy(): void {
  try {
    if (isPhonePlatform()) {
      if (viewport.isFullscreen()) return;
      if (!viewport.requestFullscreen.isAvailable()) return;
      viewport.requestFullscreen().catch(() => {
        // UNSUPPORTED (клиент < Bot API 8.0) / ALREADY_FULLSCREEN — остаёмся как есть,
        // вёрстка корректна в обоих режимах (врезки тогда просто нулевые).
      });
      return;
    }

    if (!viewport.isFullscreen()) return;
    if (!viewport.exitFullscreen.isAvailable()) return;
    viewport.exitFullscreen().catch(() => {
      // Хост отказал — остаёмся в полноэкранном режиме, вёрстка от этого не ломается.
    });
  } catch (_e) {
    // Fullscreen API недоступен — пропускаем
  }
}

/**
 * Публикует геометрию хоста в CSS-переменные `--tg-viewport-*` на `:root` и держит их
 * в актуальном состоянии (SDK переподписывается сам при смене ориентации/режима).
 *
 * Нужны две верхние врезки, и складывать их надо вместе:
 *  - `--tg-viewport-safe-area-inset-top` — системная зона устройства (статус-бар, чёлка);
 *  - `--tg-viewport-content-safe-area-inset-top` — зона, занятая плавающими контролами
 *    самого Telegram («Назад», ⌄, ⋯), которые в полноэкранном режиме лежат ПОВЕРХ страницы.
 * Сумма живёт в CSS-токене `--app-inset-top` (`brand-theme.css`); в обычном (не полноэкранном)
 * режиме обе врезки равны нулю, поэтому вёрстка не меняется.
 */
function bindViewportCssVars(): void {
  try {
    if (!viewport.isCssVarsBound() && viewport.bindCssVars.isAvailable()) {
      viewport.bindCssVars();
    }
  } catch (_e) {
    // Хост не поддерживает — остаётся фолбэк на env(safe-area-inset-top)
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
 * Закрывает Mini App — единственный способ увести человека из приложения программно.
 *
 * Свернуть кодом нельзя: в протоколе Mini Apps (SDK 3.11.8, Bot API 9.5) есть `web_app_close`
 * и нет ничего для минимизации — сворачивают только руками, жестом за шапку или кнопкой ⌄.
 * Отсюда цена закрытия: состояние теряется, следующий заход — с нуля. Поэтому зовём его
 * ровно там, где человек и так уходит: «назад» с той страницы, куда его привела кнопка из
 * чата (решение PO 2026-08-15).
 */
export function closeMiniApp(): void {
  try {
    if (miniApp.close.isAvailable()) miniApp.close();
  } catch (_e) {
    // Не в среде Telegram — закрывать нечего
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
