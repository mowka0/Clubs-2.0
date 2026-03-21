import { FC, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Tabbar } from '@telegram-apps/telegram-ui';

interface TabConfig {
  readonly path: string;
  readonly label: string;
  readonly icon: string;
}

const TABS: readonly TabConfig[] = [
  { path: '/', label: 'Discovery', icon: '🔍' },
  { path: '/my-clubs', label: 'Мои клубы', icon: '👥' },
  { path: '/organizer', label: 'Организатор', icon: '⚙️' },
  { path: '/profile', label: 'Профиль', icon: '👤' },
] as const;

/** Main tab paths where the BottomTabBar should be visible */
const TAB_PATHS = new Set(TABS.map((t) => t.path));

/**
 * Determines if the BottomTabBar should be displayed for the current path.
 * Also shows on /clubs/:id and /clubs/:id/manage pages.
 */
export function isTabBarRoute(pathname: string): boolean {
  if (TAB_PATHS.has(pathname)) return true;
  return /^\/clubs\/[^/]+(\/manage)?$/.test(pathname);
}

/**
 * Bottom navigation tab bar with 4 tabs: Discovery, My Clubs, Organizer, Profile.
 * Uses Tabbar and Tabbar.Item from @telegram-apps/telegram-ui.
 * Active tab is determined by the current route path.
 */
export const BottomTabBar: FC = () => {
  const location = useLocation();
  const navigate = useNavigate();

  const handleTabClick = useCallback(
    (path: string) => {
      if (location.pathname !== path) {
        navigate(path);
      }
    },
    [location.pathname, navigate],
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
