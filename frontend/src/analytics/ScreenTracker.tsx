import { FC, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { resolveScreen } from './screens';
import { track } from './track';

/**
 * `screen_opened` на каждую смену пути (product-analytics.md § 3.2). Монтируется в Layout после
 * авторизации и интро, поэтому первый экран приложения тоже попадает в журнал. Смена только
 * `?query` или `#hash` экраном не считается — зависимость эффекта лишь от pathname.
 */
export const ScreenTracker: FC = () => {
  const { pathname } = useLocation();
  useEffect(() => {
    const screen = resolveScreen(pathname);
    if (screen) track('screen_opened', { screen: screen.screen, ...screen.props }, screen.clubId);
  }, [pathname]);
  return null;
};
