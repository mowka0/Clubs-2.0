import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppRoot } from '@telegram-apps/telegram-ui';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErrorBoundary } from 'react-error-boundary';
import '@telegram-apps/telegram-ui/dist/styles.css';
import { initTelegramSdk } from './telegram/sdk';
import { App } from './App';
import { RootErrorFallback } from './components/RootErrorFallback';

initTelegramSdk();

// Single QueryClient instance for the whole app. Defaults below match Telegram
// Mini App reality — short retries, longer staleTime to avoid re-fetching while
// the user navigates between tabs (most data is not real-time).
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,        // 30s — list pages don't need to refetch on every navigation
      gcTime: 5 * 60_000,       // 5 min — keep cached pages around for tab-switching UX
      retry: 1,                 // single retry on network blip; 401-retry handled by apiClient itself
      refetchOnWindowFocus: false, // Mini App can't really lose focus the same way browsers do
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary FallbackComponent={RootErrorFallback}>
      <QueryClientProvider client={queryClient}>
        <AppRoot platform="base">
          <App />
        </AppRoot>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>
);
