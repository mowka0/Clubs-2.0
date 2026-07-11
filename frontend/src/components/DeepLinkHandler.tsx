import { FC, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStartParam } from '../telegram/sdk';

/**
 * Монтируется один раз в корне приложения и разбирает телеграмовский tgWebAppStartParam.
 * Когда пользователь открывает приложение по Main-Mini-App-ссылке
 * t.me/<bot>?startapp=<value> (например, url-кнопки живого закрепа в чате клуба) —
 * ведёт на соответствующий deep-маршрут. Поддерживаемые префиксы:
 *   - `skladchina_<uuid>`   →  /skladchina/<uuid>
 *   - `event_<uuid>`        →  /events/<uuid>
 *   - `club_<uuid>`         →  /clubs/<uuid>
 *   - `invite_<code>`       →  /invite/<code>   (личные приглашения, club-invites)
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
    // Инвайт-код — 16 hex-символов (ClubService.generateInviteCode); диапазон в regex
    // шире на случай будущей смены длины.
    const invite = startParam.match(/^invite_([0-9a-f]{8,64})$/i);
    if (invite) {
      navigate(`/invite/${invite[1]}`, { replace: true });
      return;
    }
  }, [navigate]);

  return null;
};
