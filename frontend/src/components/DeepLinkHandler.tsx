import { FC, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStartParam } from '../telegram/sdk';
import { rememberDeepLinkLanding } from '../telegram/chatOrigin';

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

    // `replace`, а не push: заходом по ссылке приложение и НАЧИНАЕТСЯ, лишней записи истории
    // позади быть не должно. Путь запоминаем — по нему кнопка «назад» узнаёт страницу, с
    // которой внутри приложения возвращаться некуда (`chatOrigin.isChatExitPoint`).
    const land = (path: string) => {
      rememberDeepLinkLanding(path);
      navigate(path, { replace: true });
    };

    const sklad = startParam.match(/^skladchina_([0-9a-f-]{36})$/i);
    if (sklad) {
      land(`/skladchina/${sklad[1]}`);
      return;
    }
    const event = startParam.match(/^event_([0-9a-f-]{36})$/i);
    if (event) {
      land(`/events/${event[1]}`);
      return;
    }
    const club = startParam.match(/^club_([0-9a-f-]{36})$/i);
    if (club) {
      land(`/clubs/${club[1]}`);
      return;
    }
    // Инвайт-код — 16 hex-символов (ClubService.generateInviteCode); диапазон в regex
    // шире на случай будущей смены длины.
    const invite = startParam.match(/^invite_([0-9a-f]{8,64})$/i);
    if (invite) {
      land(`/invite/${invite[1]}`);
      return;
    }
  }, [navigate]);

  return null;
};
