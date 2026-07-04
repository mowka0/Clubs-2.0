import { FC, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { AppRoot } from '@telegram-apps/telegram-ui';
import { router } from './router';
import { useThemeStore } from './store/useThemeStore';

/**
 * Оболочка приложения — владеет применением темы, чтобы dark/light были синхронны
 * и в нашем CSS (через [data-theme] на <html>), и в компонентах telegram-ui
 * (через `appearance` у AppRoot).
 */
export const App: FC = () => {
  const theme = useThemeStore((s) => s.theme);
  const mode = useThemeStore((s) => s.mode);
  const syncSystem = useThemeStore((s) => s.syncSystem);

  // Отражаем активную тему на корне документа — для нашей системы CSS-токенов.
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  // В режиме следования теме хоста реагируем на смену color-scheme ОС/Telegram.
  useEffect(() => {
    if (mode !== 'system' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => syncSystem();
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, [mode, syncSystem]);

  return (
    <AppRoot platform="base" appearance={theme}>
      <RouterProvider router={router} />
    </AppRoot>
  );
};
