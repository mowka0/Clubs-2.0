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
 * а спрятанную кнопку нажимают мимо приложения — перехватить нажатие и объяснить про
 * сворачивание можно только когда кнопка наша.
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
    // Монтируем компонент BackButton, если он поддерживается
    if (mountBackButton.isAvailable()) {
      mountBackButton();
    }

    return () => {
      if (hideBackButton.isAvailable()) {
        hideBackButton();
      }
      unmountBackButton();
    };
  }, []);

  useEffect(() => {
    if (shown) {
      if (showBackButton.isAvailable()) {
        showBackButton();
      }
    } else {
      if (hideBackButton.isAvailable()) {
        hideBackButton();
      }
    }
  }, [shown]);

  useEffect(() => {
    if (!shown) return;
    if (!onBackButtonClick.isAvailable()) return;

    const handleBack = () => {
      // Нативный BackButton Telegram не всегда генерирует haptic на каждой
      // платформе/версии (замечено отсутствие на staging) — вызываем сами,
      // чтобы тап «назад» ощущался так же, как навигация внутри приложения.
      haptic.impact('light');
      // Позади пусто и под приложением лежит чат клуба — «назад» ведёт туда, а не внутрь
      // приложения. Свернуть Mini App кодом нельзя, поэтому объясняем и уступаем действие.
      const exitToChat = exitToChatRef.current;
      if (exitToChat !== undefined && !canGoBack() && isChatUnderApp()) {
        exitToChat();
        return;
      }
      navigate(-1);
    };

    const off = onBackButtonClick(handleBack);
    return off;
  }, [shown, navigate, haptic, canGoBack]);
}
