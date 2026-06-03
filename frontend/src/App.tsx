import { FC, useEffect } from 'react';
import { RouterProvider } from 'react-router-dom';
import { AppRoot } from '@telegram-apps/telegram-ui';
import { router } from './router';
import { useThemeStore } from './store/useThemeStore';

/**
 * App shell — owns theme application so dark/light stays in sync across both
 * our own CSS (via [data-theme] on <html>) and telegram-ui components
 * (via AppRoot `appearance`).
 */
export const App: FC = () => {
  const theme = useThemeStore((s) => s.theme);
  const mode = useThemeStore((s) => s.mode);
  const syncSystem = useThemeStore((s) => s.syncSystem);

  // Reflect the active theme on the document root for our token system.
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  // When following the host theme, react to OS/Telegram color-scheme changes.
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
