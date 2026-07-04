import { useMemo } from 'react';
import {
  hapticFeedbackImpactOccurred,
  hapticFeedbackNotificationOccurred,
  hapticFeedbackSelectionChanged,
} from '@telegram-apps/sdk-react';

export type ImpactStyle = 'light' | 'medium' | 'heavy' | 'rigid' | 'soft';
export type NotifyType = 'success' | 'warning' | 'error';

export interface Haptic {
  impact: (style: ImpactStyle) => void;
  notify: (type: NotifyType) => void;
  select: () => void;
}

/**
 * Единая точка входа для Telegram Haptic Feedback внутри приложения.
 *
 * Каждый метод молча ничего не делает, если базовая функция SDK недоступна
 * (Desktop-клиент ниже Bot API 6.1, браузер вне Telegram, vitest без моков).
 * Хук никогда не бросает исключений — вызывающему коду не нужен try/catch.
 *
 * Идентичность возвращаемого объекта стабильна между рендерами, поэтому его
 * безопасно использовать как зависимость в `useEffect` / `useCallback` без
 * лишних перезапусков.
 */
export function useHaptic(): Haptic {
  return useMemo<Haptic>(
    () => ({
      impact: (style) => {
        if (hapticFeedbackImpactOccurred.isAvailable()) {
          hapticFeedbackImpactOccurred(style);
        }
      },
      notify: (type) => {
        if (hapticFeedbackNotificationOccurred.isAvailable()) {
          hapticFeedbackNotificationOccurred(type);
        }
      },
      select: () => {
        if (hapticFeedbackSelectionChanged.isAvailable()) {
          hapticFeedbackSelectionChanged();
        }
      },
    }),
    [],
  );
}
