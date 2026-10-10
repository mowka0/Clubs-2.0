import { FC, ReactNode, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { useChatLinkStatusQuery } from '../../queries/chatLink';
import { useClubQuery } from '../../queries/clubs';
import { useAuthStore } from '../../store/useAuthStore';

/** Что создаётся: от этого зависят тексты и тумблер в настройках чата, который глушит пост. */
type ChatPostKind = 'event' | 'skladchina';

const COPY: Record<ChatPostKind, { title: string; body: string; proceed: string }> = {
  event: {
    title: 'Встреча появится в чате',
    body: 'Бот опубликует её в чате — участники получат уведомление. Клуб в чате вы ещё не ' +
      'показывали, и встречу увидят раньше, чем ссылку на клуб. Хотите просто потренироваться — ' +
      'выключите «Живой закреп» в настройках чата.',
    proceed: 'Создать встречу',
  },
  skladchina: {
    title: 'Сбор появится в чате',
    body: 'Бот напишет о нём в чате и будет обновлять, кто уже скинулся. Клуб в чате вы ещё не ' +
      'показывали, и сбор увидят раньше, чем ссылку на клуб. Хотите просто потренироваться — ' +
      'выключите «Статус сборов в чате» в настройках чата.',
    proceed: 'Создать сбор',
  },
};

interface ChatPostWarningSheetProps {
  kind: ChatPostKind;
  chatTitle: string | null;
  onOpenSettings: () => void;
  onProceed: () => void;
  onCancel: () => void;
}

/**
 * Предупреждение на форме встречи или сбора, пока клуб не показан в чате (PO 2026-10-10): бот
 * опубликует их в чат раньше, чем ссылку на клуб. Главная кнопка — настройки чата, где пост
 * выключается; вторая — закрыть и создавать как есть.
 */
const ChatPostWarningSheet: FC<ChatPostWarningSheetProps> = ({ kind, chatTitle, onOpenSettings, onProceed, onCancel }) => {
  const copy = COPY[kind];
  return createPortal(
    <>
      <div className="rd-sheet-overlay rd-overlay-in" onClick={onCancel} aria-hidden="true" />
      <div className="rd-sheet rd-sheet-in rd-confirm-sheet" role="dialog" aria-modal="true" aria-label={copy.title}>
        <div className="rd-sheet-grabber" aria-hidden="true" />
        <p className="rd-confirm-text">
          <b>{copy.title}{chatTitle ? ` «${chatTitle}»` : ''}</b>
          <br />
          {copy.body}
        </p>
        <div className="rd-form-actions">
          <button type="button" className="rd-btn-primary" onClick={onOpenSettings}>Настройки чата</button>
          <button type="button" className="rd-btn-outline" onClick={onProceed}>{copy.proceed}</button>
        </div>
      </div>
    </>,
    document.body,
  );
};

/**
 * `const chatPostWarning = useChatPostWarning(clubId, 'event')` — шторка, которую форма рендерит в
 * JSX. Поднимается сама при открытии формы, а не на «Создать» (PO 2026-10-10): «Настройки чата»
 * уводят со страницы, и заполненное потерялось бы. «Создать …» и тап мимо — просто закрыть и
 * заполнять дальше.
 *
 * Только владельцу: статус чата — владельческий эндпоинт, а до показа клуба в чате действует, как
 * правило, он один. Пост уйдёт, только если бот в чате и включён тумблер своей фичи.
 */
export function useChatPostWarning(clubId: string | undefined, kind: ChatPostKind): ReactNode {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const club = useClubQuery(clubId).data;
  const isOwner = !!club && club.ownerId === user?.id;
  const status = useChatLinkStatusQuery(clubId, { enabled: isOwner && club.chatLinked }).data;
  const [dismissed, setDismissed] = useState(false);

  const botInChat = status?.botStatus === 'administrator' || status?.botStatus === 'member';
  const postsToChat = kind === 'event' ? status?.livePinEnabled : status?.skladchinaStatusEnabled;
  // chatLinked — из детали клуба: при выключенном запросе кэш статуса может быть старым.
  const shouldWarn = isOwner && club.chatLinked && !!status?.linked && botInChat
    && !!postsToChat && !status.clubLinkPinned;

  if (!shouldWarn || dismissed || !clubId) return null;
  return (
    <ChatPostWarningSheet
      kind={kind}
      chatTitle={status.chatTitle ?? null}
      onOpenSettings={() => navigate(`/clubs/${clubId}/manage?tab=chat`)}
      onProceed={() => setDismissed(true)}
      onCancel={() => setDismissed(true)}
    />
  );
}
