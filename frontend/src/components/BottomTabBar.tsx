import { FC, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';

interface TabConfig {
  readonly path: string;
  readonly label: string;
  readonly icon: string; // path under /brand/ (served from public/)
}

const TABS: readonly TabConfig[] = [
  { path: '/',          label: 'Поиск',     icon: '/brand/nav-search.png' },
  { path: '/my-clubs',  label: 'Мои клубы', icon: '/brand/nav-clubs.png' },
  { path: '/events',    label: 'События',   icon: '/brand/nav-events.png' },
  { path: '/profile',   label: 'Профиль',   icon: '/brand/nav-me.png' },
] as const;

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
 * Bottom navigation tab bar — brand brass icons + dark backdrop.
 * Active tab gets a brass underline marker and full saturation;
 * inactive tabs are desaturated/dimmed (styling lives in brand-theme.css).
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
    <nav className="brand-tabbar" aria-label="Главная навигация">
      {TABS.map((tab) => {
        const isActive = location.pathname === tab.path;
        return (
          <button
            key={tab.path}
            type="button"
            className={isActive ? 'tab active' : 'tab'}
            onClick={() => handleTabClick(tab.path)}
            aria-current={isActive ? 'page' : undefined}
          >
            {isActive && <span className="indicator" aria-hidden="true" />}
            <span className="ico" style={{ backgroundImage: `url(${tab.icon})` }} aria-hidden="true" />
            {tab.label}
          </button>
        );
      })}
    </nav>
  );
};
