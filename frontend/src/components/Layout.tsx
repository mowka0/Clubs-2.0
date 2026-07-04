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
import { useClubContextStore } from '../store/useClubContextStore';
import { useOrganizerClubs } from '../queries/organizerClubs';

/**
 * Спиннер-заглушка, показывается пока подгружаются lazy-загруженные страницы.
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
 * Плавающий док + его FAB-flow «создать». Рендерится только при авторизации, чтобы
 * запрос organizer-clubs никогда не срабатывал раньше, чем появится токен.
 *
 * Экспортирован для unit-теста защиты FAB (организатор → открыть flow,
 * не-организатор → toast). Сама кнопка FAB показывается всегда.
 */
export const AppDock: FC = () => {
  const haptic = useHaptic();
  const { clubs: organizerClubs } = useOrganizerClubs();
  const canCreate = organizerClubs.length > 0;
  const [createOpen, setCreateOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  // Если юзер сейчас смотрит клуб, которым он руководит, FAB заранее выбирает этот
  // клуб и пропускает выбор — создание сразу в текущий клуб. На любом другом экране
  // (или в клубе, который он не организует) работает обычный flow.
  const clubContextId = useClubContextStore((s) => s.clubId);
  const presetClubId =
    clubContextId && organizerClubs.some((c) => c.id === clubContextId) ? clubContextId : null;

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
      <BottomTabBar onCreate={handleCreate} scoped={presetClubId !== null} />
      {canCreate && (
        <CreateActivityFlow
          open={createOpen}
          organizerClubs={organizerClubs}
          presetClubId={presetClubId}
          onClose={() => setCreateOpen(false)}
        />
      )}
      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </>
  );
};

/**
 * Корневой компонент layout.
 *
 * - Рендерит Telegram BackButton на вложенных (не-таб) страницах.
 * - Оборачивает дочерние роуты в React Suspense для code-split lazy loading.
 * - Рендерит плавающий док (с его create-flow) после авторизации.
 */
export const Layout: FC = () => {
  const location = useLocation();
  const showTabBar = isTabBarRoute(location.pathname);
  const { isAuthenticated, isLoading, error, login } = useAuthStore();

  // Инициализируем авторизацию один раз при старте приложения, чтобы токен был доступен
  // до того, как дочерние страницы начнут запрашивать данные
  useEffect(() => {
    if (!isAuthenticated && !isLoading && !error) login();
  }, [isAuthenticated, isLoading, error, login]);

  // Показываем Telegram BackButton только на вложенных страницах (где док скрыт)
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
