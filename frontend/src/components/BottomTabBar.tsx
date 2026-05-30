import { FC, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { useMyClubsActionCountsQuery } from '../queries/applications';
import { useSkladchinaActionRequiredCountQuery } from '../queries/skladchina';

interface TabConfig {
  readonly path: string;
  readonly label: string;
  readonly icon: string; // path under /brand/ (served from public/)
}

const TABS: readonly TabConfig[] = [
  { path: '/',          label: 'Поиск',      icon: '/brand/nav-search.png' },
  { path: '/my-clubs',  label: 'Мои клубы',  icon: '/brand/nav-clubs.png' },
  { path: '/activities', label: 'Активности', icon: '/brand/nav-events.png' },
  { path: '/profile',   label: 'Профиль',    icon: '/brand/nav-me.png' },
] as const;

const TAB_PATHS = new Set(TABS.map((t) => t.path));

// Routes that should keep the "Активности" tab visually active (sub-segments + details).
const ACTIVITIES_SECONDARY_PATHS = new Set<string>(['/events', '/skladchina']);

/**
 * Determines if the BottomTabBar should be displayed for the current path.
 * Also shows on /clubs/:id, /clubs/:id/manage, /events/:id, /skladchina/:id detail
 * pages and segmented routes (/events, /skladchina) so users can switch tabs without
 * losing context.
 */
export function isTabBarRoute(pathname: string): boolean {
  if (TAB_PATHS.has(pathname)) return true;
  if (ACTIVITIES_SECONDARY_PATHS.has(pathname)) return true;
  return /^\/(clubs|events|skladchina)\/[^/]+(\/manage|\/skladchina\/new)?$/.test(pathname);
}

/** Активный таб для текущего pathname (учитывает sub-segments активностей и детальные страницы). */
function resolveActivePath(pathname: string): string {
  if (ACTIVITIES_SECONDARY_PATHS.has(pathname)) return '/activities';
  if (/^\/(events|skladchina)\/[^/]+$/.test(pathname)) return '/activities';
  if (/^\/clubs\/[^/]+\/skladchina\/new$/.test(pathname)) return '/my-clubs';
  return pathname;
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

  const { data: unpaidCount = 0 } = useSkladchinaActionRequiredCountQuery();
  // Combined counter: organizer-side pending applications + applicant-side
  // approved-but-unpaid applications. Either > 0 lights up the «Мои клубы» dot.
  const { data: myClubsActionCounts } = useMyClubsActionCountsQuery();
  const myClubsActionTotal =
    (myClubsActionCounts?.inboxCount ?? 0) +
    (myClubsActionCounts?.awaitingPaymentCount ?? 0);

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

  const activePath = resolveActivePath(location.pathname);

  return (
    <nav className="brand-tabbar" aria-label="Главная навигация">
      {TABS.map((tab) => {
        const isActive = activePath === tab.path;
        const showUnpaidDot = tab.path === '/activities' && unpaidCount > 0;
        const showInboxDot = tab.path === '/my-clubs' && myClubsActionTotal > 0;
        return (
          <button
            key={tab.path}
            type="button"
            className={isActive ? 'tab active' : 'tab'}
            onClick={() => handleTabClick(tab.path)}
            aria-current={isActive ? 'page' : undefined}
          >
            {isActive && <span className="indicator" aria-hidden="true" />}
            {showUnpaidDot && <span className="tab-dot" aria-label="Есть неоплаченный сбор" />}
            {showInboxDot && (
              <span
                className="tab-dot"
                aria-label="Есть необработанные заявки или ожидают оплаты"
              />
            )}
            <span className="ico" style={{ backgroundImage: `url(${tab.icon})` }} aria-hidden="true" />
            {tab.label}
          </button>
        );
      })}
    </nav>
  );
};
