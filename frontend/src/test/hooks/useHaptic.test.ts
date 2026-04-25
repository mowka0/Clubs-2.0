import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';

// Mock the SDK module before importing the hook so the hook captures the mocks.
// Each function carries an `isAvailable()` method we can flip per-test.
vi.mock('@telegram-apps/sdk-react', () => {
  const impactFn = vi.fn();
  const notifyFn = vi.fn();
  const selectFn = vi.fn();
  return {
    hapticFeedbackImpactOccurred: Object.assign(impactFn, { isAvailable: vi.fn(() => true) }),
    hapticFeedbackNotificationOccurred: Object.assign(notifyFn, { isAvailable: vi.fn(() => true) }),
    hapticFeedbackSelectionChanged: Object.assign(selectFn, { isAvailable: vi.fn(() => true) }),
  };
});

import {
  hapticFeedbackImpactOccurred,
  hapticFeedbackNotificationOccurred,
  hapticFeedbackSelectionChanged,
} from '@telegram-apps/sdk-react';
import { useHaptic } from '../../hooks/useHaptic';

const mockImpact = vi.mocked(hapticFeedbackImpactOccurred);
const mockNotify = vi.mocked(hapticFeedbackNotificationOccurred);
const mockSelect = vi.mocked(hapticFeedbackSelectionChanged);

describe('useHaptic', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // default: SDK available
    vi.mocked(mockImpact.isAvailable).mockReturnValue(true);
    vi.mocked(mockNotify.isAvailable).mockReturnValue(true);
    vi.mocked(mockSelect.isAvailable).mockReturnValue(true);
  });

  describe('AC-2: silent no-op when SDK unavailable', () => {
    it('does not throw and does not invoke SDK when isAvailable returns false', () => {
      vi.mocked(mockImpact.isAvailable).mockReturnValue(false);
      vi.mocked(mockNotify.isAvailable).mockReturnValue(false);
      vi.mocked(mockSelect.isAvailable).mockReturnValue(false);

      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      const { result } = renderHook(() => useHaptic());

      expect(() => result.current.impact('light')).not.toThrow();
      expect(() => result.current.notify('success')).not.toThrow();
      expect(() => result.current.select()).not.toThrow();

      expect(mockImpact).not.toHaveBeenCalled();
      expect(mockNotify).not.toHaveBeenCalled();
      expect(mockSelect).not.toHaveBeenCalled();

      expect(warnSpy).not.toHaveBeenCalled();
      expect(errorSpy).not.toHaveBeenCalled();

      warnSpy.mockRestore();
      errorSpy.mockRestore();
    });
  });

  describe('AC-1: invokes SDK with correct args when available', () => {
    it('impact() forwards the style argument to hapticFeedbackImpactOccurred', () => {
      const { result } = renderHook(() => useHaptic());

      result.current.impact('medium');

      expect(mockImpact).toHaveBeenCalledTimes(1);
      expect(mockImpact).toHaveBeenCalledWith('medium');
    });

    it('notify() forwards the type argument to hapticFeedbackNotificationOccurred', () => {
      const { result } = renderHook(() => useHaptic());

      result.current.notify('error');

      expect(mockNotify).toHaveBeenCalledTimes(1);
      expect(mockNotify).toHaveBeenCalledWith('error');
    });

    it('select() invokes hapticFeedbackSelectionChanged without args', () => {
      const { result } = renderHook(() => useHaptic());

      result.current.select();

      expect(mockSelect).toHaveBeenCalledTimes(1);
      expect(mockSelect).toHaveBeenCalledWith();
    });

    it('skips SDK when isAvailable flips to false mid-session', () => {
      const { result } = renderHook(() => useHaptic());

      vi.mocked(mockImpact.isAvailable).mockReturnValue(false);
      result.current.impact('heavy');

      expect(mockImpact).not.toHaveBeenCalled();
    });
  });

  describe('AC-3: stable reference across renders', () => {
    it('returns the same object identity on rerender', () => {
      const { result, rerender } = renderHook(() => useHaptic());

      const first = result.current;
      rerender();
      const second = result.current;

      expect(second).toBe(first);
    });

    it('keeps stable identity across multiple rerenders', () => {
      const { result, rerender } = renderHook(() => useHaptic());

      const first = result.current;
      rerender();
      rerender();
      rerender();

      expect(result.current).toBe(first);
    });
  });
});
