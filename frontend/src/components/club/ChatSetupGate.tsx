import { FC, useEffect, useState } from 'react';
import { useChatLinkStatusQuery } from '../../queries/chatLink';
import { useClubQuery } from '../../queries/clubs';
import { useAuthStore } from '../../store/useAuthStore';
import { useClubContextStore } from '../../store/useClubContextStore';
import { forgetChatLinking, readPendingChatLinkClubId } from '../../utils/chatLinkPending';
import { ChatSetupModal } from './ChatSetupModal';
import type { ChatLinkStatusDto } from '../../types/api';

/**
 * Один раз за запуск приложения: сколько бы клубов человек ни открыл, напоминание про права
 * всплывает единожды. Живёт в модуле, а не в storage, — «заход в приложение» и есть жизнь
 * этого модуля (просьба PO 2026-08-19).
 */
let rightsReminderShown = false;

/** Только для тестов: «заход в приложение» начинается заново между прогонами. */
export function resetRightsReminderForTests(): void {
  rightsReminderShown = false;
}

/** Все ли права выданы. Пока хоть одного нет, часть функций чата молчит. */
function hasEveryRight(status: ChatLinkStatusDto): boolean {
  return status.canPinMessages && status.canInviteUsers && status.canRestrictMembers && status.canManageTags;
}

/**
 * Окно со статусом подключения бота — где бы человек в приложении ни оказался.
 *
 * Два повода показать:
 *
 * 1. **Человек только что вернулся из Telegram**, куда уходил привязывать чат. Отметку ставит
 *    кнопка привязки (`chatLinkPending`); тап уводит из Mini App (на iOS вместе с закрытием),
 *    и возвращается человек на любой экран. Ждать, пока он сам дойдёт до «Управления», нельзя:
 *    без выданных прав чат наполовину мёртв, а понять это неоткуда (просьба PO 2026-08-15).
 * 2. **У открытого клуба боту не хватает прав** — независимо от того, как этот клуб появился.
 *    Клуб, рождённый из чата, отметки о привязке не оставляет (её создаёт бот, а не приложение),
 *    и напоминание не всплывало вовсе, хотя прав не хватало ровно так же (баг PO 2026-08-19).
 *
 * Второй повод — не чаще раза за запуск: это напоминание, а не блокер.
 */
export const ChatSetupGate: FC = () => {
  const [pendingClubId] = useState(() => readPendingChatLinkClubId(Date.now()));
  const [closed, setClosed] = useState(false);
  const user = useAuthStore((s) => s.user);
  // Клуб, чью страницу человек открыл. Отметка о привязке важнее: она означает, что он ушёл
  // настраивать чат минуту назад и ждёт ответа именно про него.
  const contextClubId = useClubContextStore((s) => s.clubId);
  const clubId = pendingClubId ?? contextClubId;

  // Статус привязки владельческий (403 у остальных). По отметке спрашиваем без оговорок — её
  // ставит сам владелец, уходя привязывать чат. А вот напоминание по открытому клубу проверяем:
  // туда заходит кто угодно, и чужой клуб ответил бы 403. Деталька клуба уже в кэше страницы.
  const clubQuery = useClubQuery(clubId ?? undefined);
  const club = clubQuery.data;
  const isOwner = !!club && club.ownerId === user?.id;
  const statusQuery = useChatLinkStatusQuery(clubId ?? undefined, {
    enabled: Boolean(clubId) && !closed && (Boolean(pendingClubId) || (isOwner && club.chatLinked)),
  });
  const status = statusQuery.data;
  // Вернулся из Telegram — показываем всегда: он ждёт ответа «получилось ли».
  const isReturningFromLinking = Boolean(pendingClubId);

  // Напоминание про права решается в эффекте, а не в рендере: флаг «уже показывали» —
  // побочный эффект, а рендер обязан оставаться чистым.
  const [remindAboutRights, setRemindAboutRights] = useState(false);
  useEffect(() => {
    if (isReturningFromLinking || !status?.linked) return;
    if (rightsReminderShown || hasEveryRight(status)) return;
    rightsReminderShown = true;
    setRemindAboutRights(true);
  }, [isReturningFromLinking, status]);

  if (!clubId || closed || !status?.linked) return null;
  if (!isReturningFromLinking && !remindAboutRights) return null;

  return (
    <ChatSetupModal
      clubId={clubId}
      clubName={club?.name}
      status={status}
      onClose={() => {
        forgetChatLinking();
        setClosed(true);
      }}
    />
  );
};
