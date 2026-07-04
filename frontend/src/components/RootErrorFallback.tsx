import type { FallbackProps } from 'react-error-boundary';
import { Button, Placeholder } from '@telegram-apps/telegram-ui';

/**
 * Запасной вариант последней инстанции, рендерится корневым ErrorBoundary в main.tsx,
 * когда необработанная ошибка дерева рендера доходит до корня. В норме не должен быть
 * виден — ошибки уровня фичи обрабатываются fallback'ами конкретной страницы
 * (состояние загрузки, инлайн-ошибки, toast). Этот компонент предотвращает полностью
 * пустой экран, если что-то неожиданное просочилось наверх.
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
