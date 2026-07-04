import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  mountBackButton,
  unmountBackButton,
  showBackButton,
  hideBackButton,
  onBackButtonClick,
} from '@telegram-apps/sdk-react';
import { useHaptic } from './useHaptic';

/**
 * Управляет видимостью и поведением Telegram BackButton.
 *
 * На главных таб-страницах (/, /my-clubs, /events, /profile) BackButton скрыт.
 * На вложенных страницах (детали клуба, детали события, приглашение и т.д.) BackButton
 * показан и по клику переходит назад в истории браузера.
 */
export function useBackButton(visible: boolean): void {
  const navigate = useNavigate();
  const haptic = useHaptic();

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
    if (visible) {
      if (showBackButton.isAvailable()) {
        showBackButton();
      }
    } else {
      if (hideBackButton.isAvailable()) {
        hideBackButton();
      }
    }
  }, [visible]);

  useEffect(() => {
    if (!visible) return;
    if (!onBackButtonClick.isAvailable()) return;

    const handleBack = () => {
      // Нативный BackButton Telegram не всегда генерирует haptic на каждой
      // платформе/версии (замечено отсутствие на staging) — вызываем сами,
      // чтобы тап «назад» ощущался так же, как навигация внутри приложения.
      haptic.impact('light');
      navigate(-1);
    };

    const off = onBackButtonClick(handleBack);
    return off;
  }, [visible, navigate, haptic]);
}
