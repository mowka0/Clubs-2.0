import { retrieveLaunchParams } from '@telegram-apps/sdk-react';
import { isPhonePlatform } from './platform';

/**
 * Отвечает на один вопрос: лежит ли чат этого клуба прямо под приложением.
 *
 * Приложение, открытое url-кнопкой из чата клуба (закреп встречи, «Открыть клуб», пост сбора),
 * Telegram показывает ПОВЕРХ этого чата. Просьба «открой чат» в таком контексте бессмысленна —
 * переходить некуда, экран мигает и остаётся Mini App (баг прода 2026-08-11). Вместо перехода
 * пилюля «💬 В чат» показывает подсказку «сверните приложение».
 *
 * Какой именно чат под нами, Telegram не сообщает: `chat_instance` с нашим `chat_id` не
 * сопоставим, а `chat_type` попросту врёт (см. ниже). Зато мы знаем своё: кнопки со `startapp`
 * бот кладёт ТОЛЬКО в чат клуба, а DeepLinkHandler сразу ведёт на страницу этого клуба (или
 * его встречи/сбора). Значит при таком запуске первый клуб, чью страницу приложение показало
 * за сеанс, и есть хозяин чата-источника.
 *
 * Модуль намеренно не зависит от `telegram/sdk`: тот частично замокан в доброй половине тестов
 * страниц, а сюда мы попадаем с каждой клубной страницы.
 */

/**
 * Payload'ы `startapp`, которые бот кладёт ТОЛЬКО в чат клуба: `club_…` — закреплённая ссылка
 * на клуб и приглашение при привязке, `event_…` — живой закреп встречи, `skladchina_…` — пост
 * сбора. В личке бот шлёт WebApp-кнопки, у которых `startapp` не приходит вовсе, а личные
 * приглашения (`invite_…`) в этот список намеренно не входят — их шлют друг другу люди.
 *
 * Почему не `chat_type`, который вроде бы создан ровно для этого: Telegram отдаёт по нему
 * `private` даже когда приложение открыто кнопкой из группы (замер PO 2026-08-12, macOS) —
 * контекстом он считает диалог с ботом, которому принадлежит Mini App. Поле бесполезно.
 */
const CLUB_CHAT_START_PARAM = /^(club|event|skladchina)_/i;

/** Клуб-хозяин чата, из которого открыто приложение. null — открыто не из чата клуба. */
let sourceClubId: string | null = null;
/** Первый клуб уже зафиксирован — дальше человек ходит по приложению, и это уже не источник. */
let sourceResolved = false;

/**
 * Регистрирует клуб очередной открытой клубной страницы. Вызывается из `useSetClubContext` —
 * единственного места, где сходятся все страницы внутри клуба; запоминается только первый.
 */
export function rememberClubShown(clubId: string): void {
  if (sourceResolved) return;
  sourceResolved = true;
  sourceClubId = isLaunchedFromClubChat() ? clubId : null;
}

/**
 * Лежит ли чат этого клуба под приложением. Только на телефонах: на компьютере Mini App —
 * отдельное окно рядом с чатом, «сверните приложение» там не имеет смысла, и переход
 * по ссылке работает как обычно.
 */
export function isClubChatUnderApp(clubId: string): boolean {
  return sourceClubId === clubId && isPhonePlatform();
}

/**
 * Открыто ли приложение кнопкой из чата клуба — по payload'у `startapp`.
 *
 * Чтение launch-параметров продублировано (`getStartParam` в `telegram/sdk` делает то же
 * самое) намеренно: `telegram/sdk` частично замокан в тестах страниц, а сюда мы попадаем
 * с каждой клубной страницы.
 */
function isLaunchedFromClubChat(): boolean {
  return CLUB_CHAT_START_PARAM.test(readStartParam() ?? '');
}

function readStartParam(): string | null {
  try {
    const fromParams = retrieveLaunchParams().tgWebAppStartParam;
    if (fromParams) return fromParams;
  } catch (_e) {
    // Не в среде Telegram
  }
  // Фолбэк на нативный Telegram WebApp API — как в getStartParam
  return (window as unknown as {
    Telegram?: { WebApp?: { initDataUnsafe?: { start_param?: string } } };
  })?.Telegram?.WebApp?.initDataUnsafe?.start_param ?? null;
}

/** Только для тестов: сбрасывает зафиксированный источник между прогонами. */
export function resetChatOriginForTests(): void {
  sourceClubId = null;
  sourceResolved = false;
}
