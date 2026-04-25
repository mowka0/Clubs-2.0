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
 * Single entry point for Telegram Haptic Feedback inside the app.
 *
 * Each method silently no-ops when the underlying SDK function is unavailable
 * (Desktop client below Bot API 6.1, browser outside Telegram, vitest without
 * mocks). The hook never throws — call sites do not need try/catch.
 *
 * The returned object identity is stable across renders, so it is safe to use
 * as a dependency in `useEffect` / `useCallback` without triggering reruns.
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
