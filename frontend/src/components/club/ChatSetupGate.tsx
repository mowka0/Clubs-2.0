import { FC, useState } from 'react';
import { useChatLinkStatusQuery } from '../../queries/chatLink';
import { useClubQuery } from '../../queries/clubs';
import { forgetChatLinking, readPendingChatLinkClubId } from '../../utils/chatLinkPending';
import { ChatSetupModal } from './ChatSetupModal';

/**
 * Ждёт возвращения организатора из Telegram после привязки чата и показывает окно со статусом
 * подключения бота — где бы он в приложении ни оказался.
 *
 * Живёт в корне, а не в табе «Чат», потому что тап по «Привязать чат» уводит из Mini App
 * (на iOS вместе с закрытием приложения), и возвращается человек на любой экран. Ждать, пока
 * он сам дойдёт до «Управления», нельзя: без выданных прав чат наполовину мёртв, а понять это
 * неоткуда (просьба PO 2026-08-15).
 *
 * Отметку об ожидании ставит кнопка привязки (`chatLinkPending`), снимает — закрытие окна:
 * не закрыл (свернул, вышел) — увидит снова, пока отметка не протухнет.
 */
export const ChatSetupGate: FC = () => {
  const [pendingClubId] = useState(() => readPendingChatLinkClubId(Date.now()));
  const [closed, setClosed] = useState(false);

  // refetchOnWindowFocus у самого запроса закрывает второй сценарий: на Android приложение
  // не закрывается, и статус нужно перечитать в момент возвращения фокуса.
  const statusQuery = useChatLinkStatusQuery(pendingClubId ?? undefined, {
    enabled: Boolean(pendingClubId) && !closed,
  });
  const status = statusQuery.data;
  // Название клуба — из детальки: у организатора клубов может быть несколько, и окно должно
  // говорить, к какому именно подключился чат. Запрос идёт только когда окно и так открывается.
  const clubQuery = useClubQuery(status?.linked ? pendingClubId ?? undefined : undefined);

  if (!pendingClubId || closed || !status?.linked) return null;

  return (
    <ChatSetupModal
      clubId={pendingClubId}
      clubName={clubQuery.data?.name}
      status={status}
      onClose={() => {
        forgetChatLinking();
        setClosed(true);
      }}
    />
  );
};
