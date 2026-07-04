import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErrorBoundary } from 'react-error-boundary';
import '@telegram-apps/telegram-ui/dist/styles.css';
import './styles/brand-theme.css';
import './styles/redesign.css';
import { initTelegramSdk } from './telegram/sdk';
import { App } from './App';
import { RootErrorFallback } from './components/RootErrorFallback';

initTelegramSdk();

// Единый экземпляр QueryClient на всё приложение. Настройки по умолчанию ниже отражают реальность
// Telegram Mini App — короткие ретраи, более длинный staleTime, чтобы не перезапрашивать данные
// при переключении табов пользователем (большинство данных не realtime).
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,        // 30с — страницам-спискам не нужно перезапрашивать данные при каждой навигации
      gcTime: 5 * 60_000,       // 5 мин — держать закэшированные страницы для UX переключения табов
      retry: 1,                 // один повтор при сбое сети; повтор 401 обрабатывает сам apiClient
      refetchOnWindowFocus: false, // Mini App по-настоящему не теряет фокус так, как обычные браузеры
    },
  },
});

// Ленивая загрузка devtools, чтобы Vite tree-shaking вырезал их из продакшен-бандла.
const Devtools = import.meta.env.DEV
  ? (await import('@tanstack/react-query-devtools')).ReactQueryDevtools
  : null;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary FallbackComponent={RootErrorFallback}>
      <QueryClientProvider client={queryClient}>
        <App />
        {Devtools ? <Devtools initialIsOpen={false} buttonPosition="bottom-left" /> : null}
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>
);
