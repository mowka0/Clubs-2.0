import { FC, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStartParam } from '../telegram/sdk';

/**
 * Mounts once at app root and inspects Telegram's tgWebAppStartParam.
 * When user opens app via t.me/<bot>/app?startapp=<value> link — navigates
 * to the corresponding deep route. Supported prefixes:
 *   - `skladchina_<uuid>`   →  /skladchina/<uuid>
 *   - `event_<uuid>`        →  /events/<uuid>
 *   - `club_<uuid>`         →  /clubs/<uuid>
 *
 * Idempotent — runs only on first mount per session; subsequent renders skip.
 */
export const DeepLinkHandler: FC = () => {
  const navigate = useNavigate();
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;

    const startParam = getStartParam();
    if (!startParam) return;

    const sklad = startParam.match(/^skladchina_([0-9a-f-]{36})$/i);
    if (sklad) {
      navigate(`/skladchina/${sklad[1]}`, { replace: true });
      return;
    }
    const event = startParam.match(/^event_([0-9a-f-]{36})$/i);
    if (event) {
      navigate(`/events/${event[1]}`, { replace: true });
      return;
    }
    const club = startParam.match(/^club_([0-9a-f-]{36})$/i);
    if (club) {
      navigate(`/clubs/${club[1]}`, { replace: true });
      return;
    }
  }, [navigate]);

  return null;
};
