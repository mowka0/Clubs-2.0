import { FC, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Tabbar } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../hooks/useHaptic';

interface TabConfig {
  readonly path: string;
  readonly label: string;
  readonly icon: string;
}

const TABS: readonly TabConfig[] = [
  { path: '/', label: 'Поиск', icon: '🔍' },
  { path: '/my-clubs', label: 'Мои клубы', icon: '👥' },
  { path: '/events', label: 'События', icon: '📅' },
  { path: '/profile', label: 'Профиль', icon: '👤' },
] as const;

/** Main tab paths where the BottomTabBar should be visible */
const TAB_PATHS = new Set(TABS.map((t) => t.path));

/**
 * Determines if the BottomTabBar should be displayed for the current path.
 * Also shows on /clubs/:id, /clubs/:id/manage, and /events/:id detail pages
 * so users can switch tabs without losing context.
 */
export function isTabBarRoute(pathname: string): boolean {
  if (TAB_PATHS.has(pathname)) return true;
  return /^\/(clubs|events)\/[^/]+(\/manage)?$/.test(pathname);
}

/**
 * Bottom navigation tab bar with 4 tabs: Поиск, Мои клубы, События, Профиль.
 * Uses Tabbar and Tabbar.Item from @telegram-apps/telegram-ui.
 * Active tab is determined by the current route path.
 */
export const BottomTabBar: FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const handleTabClick = useCallback(
    (path: string) => {
      if (location.pathname !== path) {
        haptic.select();
        navigate(path);
      }
    },
    [location.pathname, navigate, haptic],
  );

  if (!isTabBarRoute(location.pathname)) {
    return null;
  }

  return (
    <Tabbar>
      {TABS.map((tab) => (
        <Tabbar.Item
          key={tab.path}
          text={tab.label}
          selected={location.pathname === tab.path}
          onClick={() => handleTabClick(tab.path)}
        >
          <TabIcon icon={tab.icon} />
        </Tabbar.Item>
      ))}
    </Tabbar>
  );
};

/** Simple icon wrapper sized 28x28 as expected by TabbarItem */
const TabIcon: FC<{ icon: string }> = ({ icon }) => (
  <span
    style={{
      fontSize: 24,
      width: 28,
      height: 28,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}
    role="img"
  >
    {icon}
  </span>
);
