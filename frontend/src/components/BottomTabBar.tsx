import { FC, ReactNode, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHaptic } from '../hooks/useHaptic';
import { useMyClubsActionCountsQuery } from '../queries/applications';
import { useSkladchinaActionRequiredCountQuery } from '../queries/skladchina';

interface TabConfig {
  readonly path: string;
  readonly label: string;
  readonly icon: ReactNode;
}

const Icon = ({ children }: { children: ReactNode }) => (
  <svg
    className="rd-ico"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    {children}
  </svg>
);

// Order & labels follow the redesign mockup; routes are unchanged.
const TABS: readonly TabConfig[] = [
  { path: '/', label: 'Главная', icon: <Icon><path d="M3 11.5 12 4l9 7.5" /><path d="M5 10v10h14V10" /></Icon> },
  { path: '/activities', label: 'Активности', icon: <Icon><rect x="3" y="4" width="18" height="17" rx="2" /><path d="M3 9h18M8 2v4M16 2v4" /></Icon> },
  { path: '/my-clubs', label: 'Клубы', icon: <Icon><circle cx="9" cy="8" r="3" /><path d="M15 11a3 3 0 1 0-2-5.2" /><path d="M3 20c0-3 2.7-5 6-5s6 2 6 5" /><path d="M16 15c2.4.5 5 2.3 5 5" /></Icon> },
  { path: '/profile', label: 'Профиль', icon: <Icon><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.6-7 8-7s8 3 8 7" /></Icon> },
] as const;

const TAB_PATHS = new Set(TABS.map((t) => t.path));

// Routes that should keep the "Активности" tab visually active (sub-segments + details).
const ACTIVITIES_SECONDARY_PATHS = new Set<string>(['/events', '/skladchina']);

/**
 * Determines if the dock should be displayed for the current path.
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

interface BottomTabBarProps {
  /** Invoked by the dock's FAB — opens the unified create-activity flow. */
  onCreate: () => void;
}

/**
 * Floating dock — a glass pill with 4 tabs plus a separate circular FAB on the
 * right (always "create"). Notification dots sit on the «Клубы» and «Активности»
 * tabs; the FAB carries no badge. Styling lives in redesign.css (`.rd-dock*`).
 */
export const BottomTabBar: FC<BottomTabBarProps> = ({ onCreate }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const haptic = useHaptic();

  const { data: unpaidCount = 0 } = useSkladchinaActionRequiredCountQuery();
  // Combined counter: organizer-side pending applications + applicant-side
  // approved-but-unpaid + organizer-side approved applicants who haven't paid.
  // Any > 0 lights up the «Клубы» dot.
  const { data: myClubsActionCounts } = useMyClubsActionCountsQuery();
  const myClubsActionTotal =
    (myClubsActionCounts?.inboxCount ?? 0) +
    (myClubsActionCounts?.awaitingPaymentCount ?? 0) +
    (myClubsActionCounts?.organizerAwaitingPaymentCount ?? 0);

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
    <nav className="rd-dock" aria-label="Главная навигация">
      <div className="rd-dock-pill">
        {TABS.map((tab) => {
          const isActive = activePath === tab.path;
          const showDot =
            (tab.path === '/activities' && unpaidCount > 0) ||
            (tab.path === '/my-clubs' && myClubsActionTotal > 0);
          return (
            <button
              key={tab.path}
              type="button"
              className={isActive ? 'rd-dock-item rd-active' : 'rd-dock-item'}
              onClick={() => handleTabClick(tab.path)}
              aria-current={isActive ? 'page' : undefined}
            >
              {showDot && <span className="rd-dot" aria-hidden="true" />}
              {tab.icon}
              {tab.label}
            </button>
          );
        })}
      </div>
      <button
        type="button"
        className="rd-dock-action"
        onClick={onCreate}
        aria-label="Создать активность"
      >
        +
      </button>
    </nav>
  );
};
