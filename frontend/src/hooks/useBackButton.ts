import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  mountBackButton,
  unmountBackButton,
  showBackButton,
  hideBackButton,
  onBackButtonClick,
} from '@telegram-apps/sdk-react';
import { useHaptic } from './useHaptic';
import { useHistoryPosition } from './useHistoryPosition';
import { isChatExitPoint, isChatUnderApp } from '../telegram/chatOrigin';
import { useCloseConfirmStore } from '../store/useCloseConfirmStore';

/**
 * Нативная кнопка одна, а хук монтируют и Layout (с колбэком выхода в чат), и сама страница:
 * на одно нажатие срабатывают оба обработчика. Действует первый зарегистрированный (Layout),
 * остальные в том же тике молчат — иначе Layout закрывал приложение, а страница тут же
 * открывала вопрос «закрыть?» (staging 2026-10-06).
 */
let pressClaimed = false;
function claimPress(): boolean {
  if (pressClaimed) return false;
  pressClaimed = true;
  queueMicrotask(() => { pressClaimed = false; });
  return true;
}

/**
 * Кнопка одна, а хуков на экране два — Layout и страница. Каждый не управляет ею, а «просит
 * показать»: кнопка видна, пока просит хоть один, и прячется, когда не просит никто. Раньше
 * каждый показывал и прятал сам, и порядок эффектов решал исход: уходя со страницы, открытой
 * кнопкой из чата, Layout прятал кнопку уже после того, как следующая страница её показала, —
 * в «Управлении» вместо «Назад» оставалось «Закрыть» (PO 2026-10-08).
 */
let showRequests = 0;
/** Сколько хуков смонтировано: компонент кнопки снимает последний из них. */
let mountedHooks = 0;

function applyVisibility(): void {
  if (showRequests > 0) {
    if (showBackButton.isAvailable()) showBackButton();
  } else if (hideBackButton.isAvailable()) {
    hideBackButton();
  }
}

/**
 * Управляет видимостью и поведением Telegram BackButton.
 *
 * На главных таб-страницах (/, /my-clubs, /events, /profile) BackButton скрыт.
 * На вложенных страницах (детали клуба, детали события, приглашение и т.д.) BackButton
 * показан и по клику переходит назад в истории браузера.
 *
 * `onExitToChat` — что делать вместо перехода, когда «назад» упирается в чат клуба:
 * приложение открыто кнопкой из чата и стоит на той самой странице, куда эта кнопка привела
 * (DeepLinkHandler заходит через `replace`, поэтому позади в истории пусто). Вернуться человек
 * хочет в чат, а перехода туда нет: `navigate(-1)` в такой позиции — молчаливый холостой ход,
 * кнопка выглядит сломанной. Колбэк даётся не всеми — кто его не передал, работает как раньше.
 *
 * Ради этого же случая кнопка ПОКАЗЫВАЕТСЯ там, где обычно спрятана: deep link из чата
 * приводит на детальные страницы с доком (`/events/:id`, `/clubs/:id`, `/skladchina/:id`),
 * а спрятанную кнопку нажимают мимо приложения — перехватить нажатие можно только когда
 * кнопка наша.
 *
 * Позади пусто, а чата под приложением нет (ссылка из лички, приглашение, возврат с оплаты,
 * компьютер) — «назад» поднимает шторку «Закрыть приложение?» (PO 2026-10-06): раньше
 * `navigate(-1)` в этой позиции молчал, и кнопка выглядела сломанной.
 */
export function useBackButton(visible: boolean, onExitToChat?: () => void): void {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const location = useLocation();
  const { canGoBack } = useHistoryPosition();
  // Колбэк держим в ref, а не в зависимостях: вызывающие передают его стрелкой, и подписка
  // на нативную кнопку пересоздавалась бы каждый рендер.
  const exitToChatRef = useRef(onExitToChat);
  exitToChatRef.current = onExitToChat;

  const shown = visible || (onExitToChat !== undefined && isChatExitPoint(location.pathname));

  useEffect(() => {
    mountedHooks += 1;
    // Уже смонтированную кнопку SDK не даёт смонтировать снова (`isAvailable` ложно), а
    // неудавшийся раньше mount так повторится со следующим хуком.
    if (mountBackButton.isAvailable()) mountBackButton();
    // Кнопку, оставшуюся видимой с прошлого запуска, прячем, если показывать её некому.
    applyVisibility();

    return () => {
      mountedHooks -= 1;
      if (mountedHooks > 0) return;
      if (hideBackButton.isAvailable()) hideBackButton();
      unmountBackButton();
    };
  }, []);

  useEffect(() => {
    if (!shown) return;
    showRequests += 1;
    applyVisibility();
    return () => {
      showRequests -= 1;
      applyVisibility();
    };
  }, [shown]);

  useEffect(() => {
    if (!shown) return;
    if (!onBackButtonClick.isAvailable()) return;

    const handleBack = () => {
      if (!claimPress()) return;
      // Нативный BackButton Telegram не всегда генерирует haptic на каждой
      // платформе/версии (замечено отсутствие на staging) — вызываем сами,
      // чтобы тап «назад» ощущался так же, как навигация внутри приложения.
      haptic.impact('light');
      if (!canGoBack()) {
        // Позади пусто. Под приложением чат клуба — «назад» ведёт туда без вопросов (PO
        // 2026-08-15); иначе — подтверждение и закрытие, холостого перехода больше нет.
        const exitToChat = exitToChatRef.current;
        if (exitToChat !== undefined && isChatUnderApp()) exitToChat();
        else useCloseConfirmStore.getState().ask();
        return;
      }
      navigate(-1);
    };

    const off = onBackButtonClick(handleBack);
    return off;
  }, [shown, navigate, haptic, canGoBack]);
}
