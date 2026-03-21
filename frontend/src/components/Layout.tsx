import { FC, Suspense, useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { BottomTabBar, isTabBarRoute } from './BottomTabBar';
import { useBackButton } from '../hooks/useBackButton';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Fallback spinner shown while lazy-loaded pages are being fetched.
 */
const PageFallback: FC = () => (
  <div
    style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      height: '100vh',
    }}
  >
    <Spinner size="m" />
  </div>
);

/**
 * Root layout component.
 *
 * - Renders the Telegram BackButton on nested (non-tab) pages.
 * - Wraps child routes in React Suspense for code-split lazy loading.
 * - Renders the BottomTabBar on main tab pages.
 */
export const Layout: FC = () => {
  const location = useLocation();
  const isMainTab = isTabBarRoute(location.pathname);
  const { user, login } = useAuthStore();

  // Initialize auth once on app start so user is available in all pages
  useEffect(() => {
    if (!user) login();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Show Telegram BackButton only on nested pages
  useBackButton(!isMainTab);

  return (
    <>
      <Suspense fallback={<PageFallback />}>
        <div style={{ paddingBottom: isMainTab ? 80 : 0 }}>
          <Outlet />
        </div>
      </Suspense>
      <BottomTabBar />
    </>
  );
};
