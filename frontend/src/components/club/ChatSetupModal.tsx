import { FC } from 'react';
import { Button, Modal, Spinner, Text } from '@telegram-apps/telegram-ui';
import { useRefreshChatLinkMutation } from '../../queries/chatLink';
import type { ChatLinkStatusDto } from '../../types/api';

/**
 * Окно «чат подключён» со статусом прав бота. Всплывает сразу после привязки (`ChatSetupGate`).
 *
 * Зачем оно вообще: Telegram добавляет бота в группу с ВЫКЛЮЧЕННЫМИ ползунками прав, даже когда
 * deep link их запрашивал. Организатор об этом ниоткуда не узнавал — тумблеры функций в
 * «Управлении» просто не включались, и почему, было непонятно (жалоба PO 2026-08-15).
 */

/**
 * Права бота, без которых функции чата не работают. Названия — ровно как в настройках группы
 * Telegram, чтобы организатор искал глазами то же слово, что видит на экране.
 */
const BOT_RIGHTS: readonly {
  title: string;
  why: string;
  granted: (s: ChatLinkStatusDto) => boolean;
}[] = [
  {
    title: 'Закрепление сообщений',
    why: 'живой закреп встреч и ссылка на клуб',
    granted: (s) => s.canPinMessages,
  },
  {
    title: 'Приглашение участников',
    why: 'вход в чат через заявки',
    granted: (s) => s.canInviteUsers,
  },
  {
    title: 'Блокировка пользователей',
    why: 'строгий режим: должники читают, но не пишут',
    granted: (s) => s.canRestrictMembers,
  },
  {
    title: 'Управление тегами',
    why: 'теги наград рядом с именами',
    granted: (s) => s.canManageTags,
  },
];

/** Username бота из deep link `t.me/<bot>?startgroup=…` — чтобы назвать его в инструкции. */
function botUsernameFromStartUrl(startGroupUrl: string): string | null {
  try {
    const name = new URL(startGroupUrl).pathname.replace(/^\//, '');
    return name.length > 0 ? `@${name}` : null;
  } catch {
    return null;
  }
}

interface ChatSetupModalProps {
  clubId: string;
  /** Название клуба: у организатора их может быть несколько, и он должен видеть, к какому именно
   *  подключился чат. undefined — деталька клуба ещё грузится, тогда обходимся названием чата. */
  clubName: string | undefined;
  status: ChatLinkStatusDto;
  onClose: () => void;
}

export const ChatSetupModal: FC<ChatSetupModalProps> = ({ clubId, clubName, status, onClose }) => {
  const refreshMutation = useRefreshChatLinkMutation(clubId);
  const missing = BOT_RIGHTS.filter((right) => !right.granted(status));
  const botUsername = botUsernameFromStartUrl(status.startGroupUrl);

  return (
    <Modal open onOpenChange={(v) => !v && onClose()}>
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <Text weight="2">
          ✅ Чат «{status.chatTitle ?? 'без названия'}» подключён
          {clubName ? ` к клубу «${clubName}»` : ''}
        </Text>
        <Text>
          {missing.length === 0
            ? 'Бот в чате и получил все права. Осталось включить нужные функции в «Управлении → Чат».'
            : 'Осталось выдать боту права: Telegram добавляет его с выключенными ползунками, и пока они выключены, функции чата не включатся.'}
        </Text>

        <div className="rd-cl-rights">
          {BOT_RIGHTS.map((right) => {
            const granted = right.granted(status);
            return (
              <div key={right.title} className={`rd-cl-right${granted ? ' ok' : ''}`}>
                <span className="rr-mark" aria-hidden="true">{granted ? '✓' : '○'}</span>
                <span className="rr-tx">
                  <b>{right.title}</b>
                  <span className="rr-why">{right.why}</span>
                </span>
              </div>
            );
          })}
        </div>

        {missing.length > 0 && (
          <Text>
            Откройте группу → <b>Управление группой → Администраторы</b> → выберите бота
            {botUsername ? ` ${botUsername}` : ''} и включите нужные ползунки. Потом вернитесь
            сюда и нажмите «Проверить права».
          </Text>
        )}

        <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
          <Button size="m" stretched mode="outline" onClick={onClose} disabled={refreshMutation.isPending}>
            {missing.length === 0 ? 'Отлично' : 'Понятно'}
          </Button>
          {missing.length > 0 && (
            <Button
              size="m"
              stretched
              onClick={() => refreshMutation.mutate()}
              disabled={refreshMutation.isPending}
            >
              {refreshMutation.isPending ? <Spinner size="s" /> : 'Проверить права'}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
};
