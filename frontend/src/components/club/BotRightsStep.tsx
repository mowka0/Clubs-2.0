import { FC, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { useChatLinkStatusQuery, useStartChatLinkingMutation } from '../../queries/chatLink';
import type { ChatLinkStatusDto } from '../../types/api';

interface BotRightsStepProps {
  clubId: string;
  /** Мастер пройден — уйти в клуб. */
  onFinish: () => void;
}

/** Права, без которых бот в чате наполовину мёртв, — те же, что запрашивает ссылка привязки. */
export const REQUIRED_RIGHTS: ReadonlyArray<{ key: 'canPinMessages' | 'canInviteUsers' | 'canRestrictMembers' | 'canManageTags'; label: string }> = [
  { key: 'canPinMessages', label: 'Закреплять сообщения — живой статус встречи в шапке чата' },
  { key: 'canInviteUsers', label: 'Приглашать участников — вход в чат по заявке из приложения' },
  { key: 'canRestrictMembers', label: 'Ограничивать участников — строгий режим и возврат ушедших' },
  { key: 'canManageTags', label: 'Управлять тегами — награды видны рядом с именами' },
];

/**
 * Последний шаг мастера: права бота в чате (решение PO 2026-08-17).
 *
 * Стоит здесь, а не в управлении клубом, по двум причинам. Первая: бота часто добавляет не
 * администратор группы, и Telegram в этом случае прав не выдаёт — человек уходит с рабочим
 * клубом и мёртвым ботом, не понимая, почему «ничего не работает». Вторая: выдаётся право той же
 * ссылкой, которой бота добавляли, — Telegram видит, что бот уже в группе, и просто показывает
 * экран прав. Значит достаточно одной кнопки.
 *
 * Не админ группы — ссылку можно скопировать и отдать тому, кто админ: право выдаёт только он,
 * сам себя бот повысить не может.
 */
/**
 * Все ли права у бота уже есть. По этому же признаку мастер решает, показывать ли шаг: при
 * добавлении по ссылке `?startgroup=…&admin=…` Telegram выдаёт права сразу, и просить их
 * второй раз незачем (правка PO 2026-08-18).
 */
export function hasAllBotRights(status: ChatLinkStatusDto): boolean {
  return REQUIRED_RIGHTS.every((right) => status[right.key]);
}

export const BotRightsStep: FC<BotRightsStepProps> = ({ clubId, onFinish }) => {
  const haptic = useHaptic();
  const statusQuery = useChatLinkStatusQuery(clubId);
  const startLinking = useStartChatLinkingMutation();
  const [copied, setCopied] = useState(false);

  const status = statusQuery.data;
  const allGranted = !!status && hasAllBotRights(status);

  const copyLink = async () => {
    if (!status?.startGroupUrl) return;
    haptic.impact('light');
    try {
      await navigator.clipboard.writeText(status.startGroupUrl);
      setCopied(true);
    } catch (_e) {
      // Буфер недоступен (старый вебвью, отказ в разрешении) — молча оставляем кнопку как есть:
      // ссылку всё ещё можно открыть самому и переслать из Telegram.
    }
  };

  return (
    <>
      <h1 className="rd-wz-q">{allGranted ? 'Бот готов к работе' : 'Осталось выдать боту права'}</h1>
      <p className="rd-wz-qsub">
        {allGranted
          ? 'Права выданы — опросы, закрепы и приглашения работают.'
          : 'Без прав бот молчит: не соберёт «иду / не иду», не закрепит встречу и не позовёт в чат.'}
      </p>

      {statusQuery.isPending && <div className="rd-wz-hint">Проверяем права…</div>}

      {status && (
        <>
          <div className="rd-wz-lbl">Что нужно боту</div>
          <ul className="rd-wz-rights" role="list">
            {REQUIRED_RIGHTS.map((right) => (
              <li key={right.key} className={status[right.key] ? 'rd-wz-right rd-on' : 'rd-wz-right'}>
                <span className="rd-wz-right-mark" aria-hidden="true">{status[right.key] ? '✓' : '•'}</span>
                <span>{right.label}</span>
              </li>
            ))}
          </ul>

          {!allGranted && (
            <>
              <button
                type="button"
                className="rd-btn-primary rd-wz-next"
                disabled={startLinking.isPending}
                onClick={() => {
                  haptic.impact('medium');
                  startLinking.mutate({ clubId, startGroupUrl: status.startGroupUrl, grantRightsOnly: true });
                }}
              >
                Выдать права
              </button>
              <button type="button" className="rd-ghost-btn rd-wz-skip" onClick={copyLink}>
                {copied ? 'Ссылка скопирована' : 'Скопировать ссылку для админа'}
              </button>
              <div className="rd-wz-note">
                Telegram попросит выбрать ту же группу — бот на секунду выйдет и вернётся уже с
                правами. Выдать их может только администратор группы: если это не вы, отправьте
                ему ссылку.
              </div>
            </>
          )}
        </>
      )}

      {/* Запрос статуса владельческий: со-организатору он вернёт 403, и шаг вырождается в выход. */}
      {statusQuery.isError && (
        <div className="rd-wz-hint">Права бота видны владельцу клуба — загляните в «Управление» → «Чат».</div>
      )}

      <button
        type="button"
        className={allGranted || statusQuery.isError ? 'rd-btn-primary rd-wz-next' : 'rd-ghost-btn rd-wz-skip'}
        onClick={() => { haptic.impact('light'); onFinish(); }}
      >
        Готово
      </button>
    </>
  );
};
