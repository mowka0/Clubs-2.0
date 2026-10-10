import { FC, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMyClubsQuery } from '../../queries/clubs';
import { forgetChatLinking, readPendingNewClub } from '../../utils/chatLinkPending';

/**
 * Возвращает человека на страницу клуба, только что родившегося из его чата.
 *
 * Telegram обратно в Mini App не перебрасывает: выбрав группу, человек оказывается в чате, а
 * приложение открывает заново — и попадал бы на общий экран, гадая, получилось ли (просьба PO
 * 2026-08-17). Клуб при этом создаёт бот, поэтому его id приложение узнать заранее не может.
 *
 * Ищем по разнице: перед уходом запомнили список клубов, теперь берём тот, которого в нём не
 * было. Несколько новых сразу — берём первый; такое возможно, только если человек за один
 * заход подключил два чата, и любой из них верный ответ на «покажи, что получилось».
 */
export const NewClubFromChatGate: FC = () => {
  const navigate = useNavigate();
  const [pending] = useState(() => readPendingNewClub(Date.now()));
  // refetchOnWindowFocus у запроса клубов закрывает Android: там приложение не закрывается, и
  // список нужно перечитать в момент возвращения фокуса.
  const myClubsQuery = useMyClubsQuery();

  const clubs = myClubsQuery.data;
  useEffect(() => {
    if (!pending || !clubs) return;
    const known = new Set(pending.knownClubIds);
    const fresh = clubs.find((membership) => !known.has(membership.clubId));
    if (!fresh) return;
    forgetChatLinking();
    navigate(`/clubs/${fresh.clubId}`, { replace: true });
  }, [pending, clubs, navigate]);

  return null;
};
