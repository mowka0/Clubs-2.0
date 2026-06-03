import { FC, Suspense, useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { BottomTabBar, isTabBarRoute } from './BottomTabBar';
import { DeepLinkHandler } from './DeepLinkHandler';
import { Toast } from './Toast';
import { CreateActivityFlow } from './manage/CreateActivityFlow';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useOrganizerClubs } from '../queries/organizerClubs';

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
 * Floating dock + its FAB "create" flow. Rendered only when authenticated so
 * the organizer-clubs query never fires before a token exists.
 */
const AppDock: FC = () => {
  const haptic = useHaptic();
  const { clubs: organizerClubs } = useOrganizerClubs();
  const canCreate = organizerClubs.length > 0;
  const [createOpen, setCreateOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const handleCreate = () => {
    haptic.impact('light');
    if (canCreate) {
      setCreateOpen(true);
    } else {
      setToast('Создавать активности могут организаторы клубов');
    }
  };

  return (
    <>
      <BottomTabBar onCreate={handleCreate} />
      {canCreate && (
        <CreateActivityFlow
          open={createOpen}
          organizerClubs={organizerClubs}
          onClose={() => setCreateOpen(false)}
        />
      )}
      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </>
  );
};

/**
 * Root layout component.
 *
 * - Renders the Telegram BackButton on nested (non-tab) pages.
 * - Wraps child routes in React Suspense for code-split lazy loading.
 * - Renders the floating dock (with its create flow) once authenticated.
 */
export const Layout: FC = () => {
  const location = useLocation();
  const showTabBar = isTabBarRoute(location.pathname);
  const { isAuthenticated, isLoading, error, login } = useAuthStore();

  // Initialize auth once on app start so token is available before child pages fetch data
  useEffect(() => {
    if (!isAuthenticated && !isLoading && !error) login();
  }, [isAuthenticated, isLoading, error, login]);

  // Show Telegram BackButton only on nested pages (where the dock is hidden)
  useBackButton(!showTabBar);

  if (!isAuthenticated) {
    if (error) {
      return (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            height: '100vh',
            padding: 16,
            textAlign: 'center',
            color: 'var(--tgui--destructive_text_color)',
          }}
        >
          Не удалось авторизоваться. Откройте приложение через Telegram.
        </div>
      );
    }
    return <PageFallback />;
  }

  return (
    <>
      <DeepLinkHandler />
      <Suspense fallback={<PageFallback />}>
        <div
          style={{
            paddingBottom: showTabBar
              ? 'calc(96px + env(safe-area-inset-bottom, 0px) + 12px)'
              : 0,
          }}
        >
          <Outlet />
        </div>
      </Suspense>
      <AppDock />
    </>
  );
};
