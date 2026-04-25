import type { FallbackProps } from 'react-error-boundary';
import { Button, Placeholder } from '@telegram-apps/telegram-ui';

/**
 * Last-resort fallback rendered by the top-level ErrorBoundary in main.tsx
 * when an uncaught render-tree error reaches the root. Should not normally
 * be visible — feature-level errors are handled by per-page fallbacks
 * (loading state, inline errors, toast). This component prevents a fully
 * blank screen if something unexpected escapes.
 */
export function RootErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        background: 'var(--tgui--bg_color)',
      }}
    >
      <Placeholder
        header="Что-то пошло не так"
        description={
          error instanceof Error && error.message
            ? error.message
            : 'Попробуйте перезапустить приложение.'
        }
        action={
          <Button size="m" onClick={resetErrorBoundary}>
            Попробовать снова
          </Button>
        }
      />
    </div>
  );
}
