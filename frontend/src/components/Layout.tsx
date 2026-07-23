import { FC, Suspense, useEffect, useMemo } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Spinner } from '@telegram-apps/telegram-ui';
import { BottomTabBar, isTabBarRoute } from './BottomTabBar';
import { DeepLinkHandler } from './DeepLinkHandler';
import { CreateActivityFlow } from './manage/CreateActivityFlow';
import { OnboardingFlow } from './onboarding/OnboardingFlow';
import { useBackButton } from '../hooks/useBackButton';
import { useHaptic } from '../hooks/useHaptic';
import { useAuthStore } from '../store/useAuthStore';
import { useClubContextStore } from '../store/useClubContextStore';
import { useCreateFlowStore } from '../store/useCreateFlowStore';
import { useOrganizerClubs } from '../queries/organizerClubs';
import { getStartParam } from '../telegram/sdk';

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
 * FAB открывает flow ВСЕМ: пункт «Сообщить о проблеме» общедоступен, а бывший
 * тост-гардрейл «создавать могут организаторы» переехал в состав пунктов шита
 * (не-организатор видит только обратную связь). Экспортирован для unit-теста.
 */
export const AppDock: FC = () => {
  const haptic = useHaptic();
  const { clubs: organizerClubs } = useOrganizerClubs();
  const canCreate = organizerClubs.length > 0;
  // Открытие флоу — глобальный клиентский стейт: CTA пустых состояний открывают
  // его из глубины страниц, не дублируя сам флоу и его guard'ы.
  const createOpen = useCreateFlowStore((s) => s.isOpen);
  const openCreateFlow = useCreateFlowStore((s) => s.open);
  const closeCreateFlow = useCreateFlowStore((s) => s.close);

  // Если юзер сейчас смотрит клуб, которым он руководит, FAB заранее выбирает этот
  // клуб и пропускает выбор — создание сразу в текущий клуб. На любом другом экране
  // (или в клубе, который он не организует) работает обычный flow.
  const clubContextId = useClubContextStore((s) => s.clubId);
  const presetClubId =
    clubContextId && organizerClubs.some((c) => c.id === clubContextId) ? clubContextId : null;

  const handleCreate = () => {
    haptic.impact('light');
    openCreateFlow();
  };

  return (
    <>
      <BottomTabBar onCreate={handleCreate} scoped={presetClubId !== null} />
      <CreateActivityFlow
        open={createOpen}
        canCreate={canCreate}
        organizerClubs={organizerClubs}
        presetClubId={presetClubId}
        onClose={closeCreateFlow}
      />
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
  const { user, isAuthenticated, isLoading, error, login } = useAuthStore();

  // Открыл приложение по deep-link (приглашение в клуб, событие, складчина) — он шёл
  // к конкретным людям и к конкретной вещи, а не знакомиться с продуктом. Онбординг тогда
  // ОТКЛАДЫВАЕТСЯ, а не отменяется: `onboardedAt` остаётся null, и карусель покажется
  // при следующем обычном запуске. Значение за сессию не меняется — считаем один раз.
  const hasStartParam = useMemo(() => getStartParam() !== null, []);

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

  // Профиль ещё не доехал — спиннер, не онбординг. «Данные не пришли» ≠ «данных нет»:
  // на производном признаке мы уже обжигались (F5-20), и карусель вылезала поверх
  // обжитого аккаунта. Единственное основание показать её — явный факт onboardedAt = null.
  if (!user) {
    return <PageFallback />;
  }

  // Гейт, а не роут: онбординг рендерится ВМЕСТО приложения, иначе его обошли бы навигацией.
  if (user.onboardedAt == null && !hasStartParam) {
    return <OnboardingFlow />;
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
