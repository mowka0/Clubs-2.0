import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  mountBackButton,
  unmountBackButton,
  showBackButton,
  hideBackButton,
  onBackButtonClick,
} from '@telegram-apps/sdk-react';
import { useHaptic } from './useHaptic';

/**
 * Manages the Telegram BackButton visibility and behavior.
 *
 * On main tab pages (/, /my-clubs, /organizer, /profile) the BackButton is hidden.
 * On nested pages (club detail, event detail, invite, etc.) the BackButton is shown
 * and navigates back in the browser history when clicked.
 */
export function useBackButton(visible: boolean): void {
  const navigate = useNavigate();
  const haptic = useHaptic();

  useEffect(() => {
    // Mount the BackButton component if supported
    if (mountBackButton.isAvailable()) {
      mountBackButton();
    }

    return () => {
      if (hideBackButton.isAvailable()) {
        hideBackButton();
      }
      unmountBackButton();
    };
  }, []);

  useEffect(() => {
    if (visible) {
      if (showBackButton.isAvailable()) {
        showBackButton();
      }
    } else {
      if (hideBackButton.isAvailable()) {
        hideBackButton();
      }
    }
  }, [visible]);

  useEffect(() => {
    if (!visible) return;
    if (!onBackButtonClick.isAvailable()) return;

    const handleBack = () => {
      // Telegram's native BackButton does not always emit haptic on every
      // platform/version (observed missing on staging) — fire one ourselves
      // so the back-tap feels consistent with in-app navigation.
      haptic.impact('light');
      navigate(-1);
    };

    const off = onBackButtonClick(handleBack);
    return off;
  }, [visible, navigate, haptic]);
}
