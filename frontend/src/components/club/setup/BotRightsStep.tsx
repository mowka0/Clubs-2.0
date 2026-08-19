import { FC, useState } from 'react';
import { useHaptic } from '../../../hooks/useHaptic';
import { useChatLinkStatusQuery, useStartChatLinkingMutation } from '../../../queries/chatLink';
import type { ChatLinkStatusDto } from '../../../types/api';

interface BotRightsStepProps {
  clubId: string;
  /** Мастер пройден — уйти в клуб. */
  onFinish: () => void;
}

type RightKey = 'canPinMessages' | 'canInviteUsers' | 'canRestrictMembers' | 'canManageTags';

/** Права, без которых бот в чате наполовину мёртв. Их Telegram выдаёт по ссылке привязки. */
export const REQUIRED_RIGHTS: ReadonlyArray<{ key: RightKey; label: string }> = [
  { key: 'canPinMessages', label: 'Закреплять сообщения — живой статус встречи в шапке чата' },
  { key: 'canInviteUsers', label: 'Приглашать участников — вход в чат по заявке из приложения' },
  { key: 'canRestrictMembers', label: 'Ограничивать участников — строгий режим и возврат ушедших' },
];

/**
 * «Управление тегами» стоит особняком: право новое (Bot API 9.5), и галочка на него в экране
 * добавления появляется не у всех клиентов — человеку приходится искать тумблер руками
 * (замечание PO 2026-08-19). Держать из-за него весь шаг нельзя: без тегов работает всё,
 * кроме наград рядом с именами.
 */
const OPTIONAL_RIGHT: { key: RightKey; label: string } = {
  key: 'canManageTags',
  label: 'Управлять тегами — награды участников видны рядом с именами в чате',
};

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
 * Есть ли у бота все обязательные права. По этому признаку мастер решает, показывать ли шаг:
 * при добавлении по ссылке `?startgroup=…&admin=…` Telegram выдаёт их сразу, и просить второй
 * раз незачем (правка PO 2026-08-18). Необязательные теги на решение не влияют — иначе шаг
 * висел бы у всех, кому клиент не показал эту галочку.
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

          {!status[OPTIONAL_RIGHT.key] && (
            <>
              <div className="rd-wz-lbl">Дополнительно</div>
              <p className="rd-wz-hint">
                {OPTIONAL_RIGHT.label}. Telegram это право по ссылке не выдаёт — включается только
                руками: откройте группу → профиль бота → «Изменить права» → «Управление тегами».
                Галочка появится здесь сама, когда вернётесь в приложение.
              </p>
            </>
          )}

          {!allGranted && (
            <>
              {/* Кнопки одной группой с равными промежутками: порядок — сначала главное действие,
                  следом запасной путь для тех, кто не админ группы (правка PO 2026-08-19). */}
              <div className="rd-wz-actions">
                <button
                  type="button"
                  className="rd-btn-primary"
                  disabled={startLinking.isPending}
                  onClick={() => {
                    haptic.impact('medium');
                    startLinking.mutate({ clubId, startGroupUrl: status.startGroupUrl, grantRightsOnly: true });
                  }}
                >
                  Выдать права
                </button>
                <button type="button" className="rd-ghost-btn" onClick={copyLink}>
                  {copied ? 'Ссылка скопирована' : 'Скопировать ссылку для админа'}
                </button>
              </div>
              <p className="rd-wz-admin-note">
                <b>Выдать права может только администратор группы.</b> Если это не вы — отправьте
                ему ссылку. Telegram попросит выбрать ту же группу: бот на секунду выйдет и
                вернётся уже с правами.
              </p>
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
        className={allGranted || statusQuery.isError ? 'rd-btn-primary rd-wz-next' : 'rd-ghost-btn rd-wz-done'}
        onClick={() => { haptic.impact('light'); onFinish(); }}
      >
        Готово
      </button>
    </>
  );
};
