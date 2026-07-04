import { FC, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStartParam } from '../telegram/sdk';

/**
 * Монтируется один раз в корне приложения и разбирает телеграмовский tgWebAppStartParam.
 * Когда пользователь открывает приложение по ссылке t.me/<bot>/app?startapp=<value> —
 * ведёт на соответствующий deep-маршрут. Поддерживаемые префиксы:
 *   - `skladchina_<uuid>`   →  /skladchina/<uuid>
 *   - `event_<uuid>`        →  /events/<uuid>
 *   - `club_<uuid>`         →  /clubs/<uuid>
 *
 * Идемпотентен — срабатывает только на первом монтировании за сессию; дальнейшие рендеры пропускают.
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
