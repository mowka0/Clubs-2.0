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
 * Какой именно чат под нами, Telegram не сообщает: в launch-параметрах есть только тип чата
 * (`chat_type`) и непрозрачный `chat_instance`, с нашим `chat_id` не сопоставимый. Зато мы знаем
 * своё: кнопки со `startapp` бот кладёт ТОЛЬКО в чат клуба, а DeepLinkHandler сразу ведёт на
 * страницу этого клуба (или его встречи/сбора). Значит первый клуб, чью страницу приложение
 * показало за сеанс, и есть хозяин чата-источника.
 *
 * Модуль намеренно не зависит от `telegram/sdk`: тот частично замокан в доброй половине тестов
 * страниц, а сюда мы попадаем с каждой клубной страницы.
 */

/**
 * Типы чата-источника, означающие, что Mini App открыт ПОВЕРХ группового чата. Личка —
 * это `sender` (диалог с ботом) и `private`, остальные значения из набора — группы и каналы.
 */
const GROUP_CHAT_TYPES = new Set(['group', 'supergroup', 'channel']);

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
  sourceClubId = isLaunchedFromGroupChat() ? clubId : null;
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
 * Открыто ли приложение кнопкой из группового чата, а не из лички с ботом.
 *
 * Telegram кладёт тип чата-источника в launch-параметры, но только для запусков по прямой
 * ссылке (`t.me/<bot>?startapp=…`) — то есть ровно для наших кнопок в чатах. У menu-button
 * поля нет вовсе, и это верно трактуется как «не из группы».
 */
function isLaunchedFromGroupChat(): boolean {
  try {
    const chatType = retrieveLaunchParams().tgWebAppData?.chat_type;
    if (chatType) return GROUP_CHAT_TYPES.has(chatType);
  } catch (_e) {
    // Не в среде Telegram
  }
  // Фолбэк на нативный Telegram WebApp API — как в getStartParam
  const fromNative = (window as unknown as {
    Telegram?: { WebApp?: { initDataUnsafe?: { chat_type?: string } } };
  })?.Telegram?.WebApp?.initDataUnsafe?.chat_type;
  return fromNative ? GROUP_CHAT_TYPES.has(fromNative) : false;
}

/**
 * ВРЕМЕННО (диагностика staging, 2026-08-12): что именно Telegram прислал в launch-параметрах.
 * Подсказка печатает это на экране, чтобы понять, почему запуск из чата не распознаётся.
 * Отдаёт только имена полей и тип чата — ни initData, ни user, ни hash сюда не попадают.
 * СНЯТЬ вместе с DEBUG_ALWAYS_SHOW_HINT перед мержем.
 */
export function describeChatOrigin(clubId: string): string {
  let keys = '—';
  let chatType = '—';
  let platform = '—';
  let startParam = '—';
  try {
    const params = retrieveLaunchParams();
    platform = params.tgWebAppPlatform ?? '—';
    startParam = params.tgWebAppStartParam ?? '—';
    const data = params.tgWebAppData;
    if (data) {
      keys = Object.keys(data).join(',') || 'пусто';
      chatType = data.chat_type ?? 'нет';
    } else {
      keys = 'tgWebAppData нет';
    }
  } catch (e) {
    keys = `ошибка: ${e instanceof Error ? e.message : String(e)}`;
  }
  const short = (v: string | null) => (v ? v.slice(0, 8) : 'null');
  return [
    `chat_type=${chatType}`,
    `platform=${platform}`,
    `startapp=${startParam}`,
    `src=${short(sourceClubId)}`,
    `club=${short(clubId)}`,
    `phone=${isPhonePlatform()}`,
    `поля: ${keys}`,
  ].join(' · ');
}

/** Только для тестов: сбрасывает зафиксированный источник между прогонами. */
export function resetChatOriginForTests(): void {
  sourceClubId = null;
  sourceResolved = false;
}
