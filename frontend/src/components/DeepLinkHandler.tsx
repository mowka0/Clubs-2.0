import { FC, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getStartParam } from '../telegram/sdk';
import { parseDeepLink } from '../telegram/deepLink';
import { rememberDeepLinkLanding } from '../telegram/chatOrigin';
import { useMyClubsQuery } from '../queries/clubs';
import { holdsClubSeat } from '../utils/membershipRole';
import type { MembershipDto } from '../types/api';

/**
 * Куда ведёт tgWebAppStartParam. Поддерживаемые префиксы:
 *   - `skladchina_<uuid>`   →  /skladchina/<uuid>
 *   - `event_<uuid>`        →  /events/<uuid>
 *   - `club_<uuid>`         →  /clubs/<uuid> участнику, /clubs/<uuid>/join — остальным
 *                              (кнопка «Открыть клуб» в чате; экран приглашения, PO 2026-10-08)
 *   - `billing_<uuid>`      →  /clubs/<uuid>?billing=done          (возврат после оплаты за клуб: платит любой участник)
 *   - `invite_<code>`       →  /invite/<code>   (личные приглашения, club-invites)
 * `myClubs` нужен только ссылке на клуб: пока он грузится (undefined), ответа нет; не загрузился
 * (null) — открываем клуб, его страница сама покажет гостю «Вступить».
 */
export function resolveDeepLink(
  startParam: string | null,
  myClubs?: readonly MembershipDto[] | null,
): string | null | undefined {
  const link = parseDeepLink(startParam);
  if (!link) return null;
  switch (link.kind) {
    case 'skladchina':
      return `/skladchina/${link.id}`;
    case 'event':
      return `/events/${link.id}`;
    case 'club': {
      if (myClubs === undefined) return undefined;
      if (myClubs === null) return `/clubs/${link.id}`;
      const clubId = link.id.toLowerCase();
      const isSeated = myClubs.some((m) => m.clubId.toLowerCase() === clubId && holdsClubSeat(m));
      return isSeated ? `/clubs/${link.id}` : `/clubs/${link.id}/join`;
    }
    case 'billing':
      return `/clubs/${link.id}?billing=done`;
    case 'invite':
      return `/invite/${link.id}`;
  }
}

/**
 * Переход по ссылке запуска уже случился — за сессию он один. Ставится, когда мы ушли с «/», а не
 * в момент вызова `navigate`: переход в ленивый экран идёт через transition, и пока грузится
 * чанк, на экране ещё «/» — он не должен успеть увести в «Мои клубы».
 */
let landed = false;

/**
 * Ссылка запуска ещё не отработала. Пока так, «/» никуда не уводит: иначе человек без клубов
 * уезжал в «Мои клубы» — переход «/» срабатывал после перехода по ссылке и побеждал (баг PO
 * 2026-10-08: кнопка «Открыть клуб» из чата приводила новичка не в клуб).
 */
export function isDeepLinkPending(): boolean {
  return !landed && resolveDeepLink(getStartParam(), null) !== null;
}

export function resetDeepLinkForTests(): void {
  landed = false;
}

/**
 * Монтируется один раз в корне приложения и ведёт по t.me/<bot>?startapp=<value> на нужный
 * экран (`resolveDeepLink`). Срабатывает один раз за сессию.
 */
export const DeepLinkHandler: FC = () => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const handled = useRef(false);
  // Параметр запуска за сессию не меняется — читаем один раз.
  const [startParam] = useState(getStartParam);
  // Членство нужно только ссылке на клуб; запрос общий с «Моими клубами», лишнего обращения нет.
  const myClubsQuery = useMyClubsQuery();
  const myClubs = myClubsQuery.isError ? null : myClubsQuery.data;

  useEffect(() => {
    if (handled.current) return;
    const target = resolveDeepLink(startParam, myClubs);
    if (target === undefined) return;
    handled.current = true;
    if (target === null) return;
    // `replace`, а не push: заходом по ссылке приложение и НАЧИНАЕТСЯ, лишней записи истории
    // позади быть не должно. Путь запоминаем — по нему кнопка «назад» узнаёт страницу, с
    // которой внутри приложения возвращаться некуда (`chatOrigin.isChatExitPoint`).
    rememberDeepLinkLanding(target);
    navigate(target, { replace: true });
  }, [navigate, myClubs, startParam]);

  useEffect(() => {
    if (handled.current && pathname !== '/') landed = true;
  }, [pathname]);

  return null;
};
