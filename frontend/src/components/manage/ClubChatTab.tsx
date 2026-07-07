import { FC, useState } from 'react';
import { Button, Modal, Spinner, Text } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  useChatLinkStatusQuery,
  useRefreshChatLinkMutation,
  useUnlinkChatMutation,
  useUpdateChatLinkMutation,
} from '../../queries/chatLink';
import { openTmeLink } from '../../utils/telegramLinks';
import { Toast } from '../Toast';
import type { ChatLinkStatusDto } from '../../types/api';

// Таб «Чат» в «Управлении клубом» — три состояния по мокапу 01-manage-chat-section:
// A (не привязан) → CTA привязки, B (привязан, здоров) → карточка + тумблеры фич,
// C (деградация: бот кикнут / права отняты) → алерт + «Проверить права ещё раз».
// Спека: docs/modules/club-chat-link.md

interface ClubChatTabProps {
  clubId: string;
}

function formatLinkedDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** Бот присутствует в чате (или мы этого не опровергли) — фичи в принципе доступны. */
function isBotInChat(status: ChatLinkStatusDto): boolean {
  return status.botStatus === 'administrator' || status.botStatus === 'member';
}

// ---- Состояние A: не привязан ----

const NotLinkedState: FC<{ startGroupUrl: string }> = ({ startGroupUrl }) => {
  const haptic = useHaptic();
  return (
    <div className="rd-glass" style={{ padding: 16 }}>
      <div style={{ fontSize: 14, fontWeight: 650, marginBottom: 4 }}>💬 Подключите чат клуба</div>
      <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>
        Бот станет помощником прямо в вашей группе:
      </div>
      <div className="rd-cl-ladder">
        <div className="rd-cl-step">
          <span className="n">1</span>
          <span className="t"><b>Вход через заявки</b> — новые люди попадают в чат только через одобренную заявку в клуб</span>
        </div>
        <div className="rd-cl-step">
          <span className="n">2</span>
          <span className="t"><b>Живой закреп</b> — в шапке чата всегда актуальное «кто идёт» по ближайшей встрече (скоро)</span>
        </div>
        <div className="rd-cl-step">
          <span className="n">3</span>
          <span className="t"><b>Строгий режим</b> — должники читают, но не пишут; исключённые уходят и из чата (скоро)</span>
        </div>
      </div>
      <button
        type="button"
        className="rd-btn-primary"
        onClick={() => { haptic.impact('medium'); openTmeLink(startGroupUrl); }}
      >
        Привязать чат
      </button>
      <div className="rd-cta-hint" style={{ marginTop: 8 }}>
        Откроется Telegram — выберите группу клуба.<br />
        Бот попросит права администратора: закреплять сообщения, приглашать участников и снимать блокировки.
      </div>
    </div>
  );
};

// ---- Состояния B/C: привязан ----

const LinkedState: FC<{ clubId: string; status: ChatLinkStatusDto }> = ({ clubId, status }) => {
  const haptic = useHaptic();
  const refreshMutation = useRefreshChatLinkMutation(clubId);
  const updateMutation = useUpdateChatLinkMutation(clubId);
  const unlinkMutation = useUnlinkChatMutation(clubId);

  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [showUnlinkModal, setShowUnlinkModal] = useState(false);

  const botInChat = isBotInChat(status);
  const busy = refreshMutation.isPending || updateMutation.isPending || unlinkMutation.isPending;

  // Алерт деградации: что именно сломалось и как починить (мокап 01-C).
  const alert = !botInChat
    ? { title: 'Бот удалён из чата', sub: 'Привязка сохранена: верните бота в группу и нажмите «Проверить права ещё раз» — всё оживёт само.' }
    : status.doorEnabled && !status.canInviteUsers
      ? { title: 'Бот потерял право приглашать участников', sub: 'Вход через заявки остановлен. Верните боту право «Приглашение участников» в настройках группы — и всё оживёт.' }
      : null;

  const handleToggleDoor = () => {
    if (busy) return;
    setError(null);
    haptic.impact('medium');
    updateMutation.mutate(
      { doorEnabled: !status.doorEnabled },
      {
        onSuccess: () => haptic.notify('success'),
        onError: (e) => { setError(e.message); haptic.notify('error'); },
      },
    );
  };

  const handleRefresh = () => {
    setError(null);
    haptic.impact('light');
    refreshMutation.mutate(undefined, {
      onSuccess: () => { haptic.notify('success'); setToast('Статус обновлён'); },
      onError: (e) => { setError(e.message); haptic.notify('error'); },
    });
  };

  const handleUnlink = () => {
    setError(null);
    haptic.impact('heavy');
    unlinkMutation.mutate(undefined, {
      onSuccess: () => { haptic.notify('success'); setShowUnlinkModal(false); setToast('Чат отвязан'); },
      onError: (e) => { setError(e.message); haptic.notify('error'); setShowUnlinkModal(false); },
    });
  };

  const handleCopyDoorLink = () => {
    if (!status.doorInviteLink) return;
    haptic.impact('light');
    void navigator.clipboard?.writeText(status.doorInviteLink)
      .then(() => setToast('Ссылка скопирована'))
      .catch(() => setToast(status.doorInviteLink));
  };

  return (
    <>
      {alert && (
        <div className="rd-cl-alert" role="alert">
          <span className="ai" aria-hidden="true">⚠️</span>
          <span className="at">
            <b>{alert.title}</b>
            <span className="sub">{alert.sub}</span>
          </span>
        </div>
      )}

      {/* Бот выпал из чата — быстрая повторная привязка тем же deep link'ом, не заставляя
          сначала отвязывать: повторный /start в том же чате идемпотентен (бэкенд освежит
          права и пересоздаст invite-ссылку). Реестр багов №5. */}
      {!botInChat && (
        <button
          type="button"
          className="rd-btn-primary"
          style={{ marginBottom: 12 }}
          onClick={() => { haptic.impact('medium'); openTmeLink(status.startGroupUrl); }}
        >
          Привязать бота заново
        </button>
      )}

      {/* Карточка чата + пиллы прав */}
      <div className="rd-glass" style={{ padding: 14, marginBottom: 10 }}>
        <div className="rd-cl-chat-row">
          <div className="rd-cl-ava" aria-hidden="true">{(status.chatTitle ?? '?').charAt(0).toUpperCase()}</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="rd-cl-chat-name">{status.chatTitle ?? 'Чат без названия'}</div>
            {status.linkedAt && (
              <div className="rd-cl-chat-sub">привязан {formatLinkedDate(status.linkedAt)}</div>
            )}
          </div>
        </div>
        <div className="rd-cl-pills">
          <span className={`rd-cl-pill ${botInChat ? 'ok' : 'bad'}`}>{botInChat ? '✓ бот в чате' : '✕ бот не в чате'}</span>
          <span className={`rd-cl-pill ${status.canPinMessages ? 'ok' : 'bad'}`}>{status.canPinMessages ? '✓ закреп разрешён' : '✕ закреп запрещён'}</span>
          <span className={`rd-cl-pill ${status.canInviteUsers ? 'ok' : 'bad'}`}>{status.canInviteUsers ? '✓ приглашения разрешены' : '✕ приглашения запрещены'}</span>
        </div>
        {/* Invite-ссылка живёт независимо от тумблера двери (создаётся при привязке) — по ней
            работает кнопка «Чат клуба» у участников. Реестр багов №4, текст — формулировка PO. */}
        {status.doorInviteLink && (
          <>
            <div className="rd-cl-link-row">
              <span className="rd-cl-link-text">{status.doorInviteLink}</span>
              <button type="button" className="rd-cl-copy" onClick={handleCopyDoorLink}>Копировать</button>
            </div>
            <div className="rd-cl-chat-sub" style={{ marginTop: 6 }}>
              Данная ссылка уже активна и работает, поменяйте старую, если где-то её используете.
            </div>
          </>
        )}
      </div>

      {/* Тумблеры фич: дверь — активная, закреп и строгий режим — «скоро» (следующие слайсы) */}
      <div className="rd-glass" style={{ padding: '2px 14px', marginBottom: 10 }}>
        <div className="rd-cl-feat">
          <div className="fi">
            <div className="ft">Вход в чат через заявки</div>
            <div className="fd">
              Стучащимся в чат не-участникам бот напишет правила и впустит только после одобрения
              заявки в клуб. Участников с доступом бот впускает всегда.
            </div>
          </div>
          <button
            type="button"
            className={`rd-cl-tgl${status.doorEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={status.doorEnabled}
            aria-label="Вход в чат через заявки"
            disabled={busy || (!status.doorEnabled && (!botInChat || !status.canInviteUsers))}
            onClick={handleToggleDoor}
          />
        </div>
        <div className="rd-cl-feat soon">
          <div className="fi">
            <div className="ft">Живой закреп<span className="rd-cl-soon-tag">скоро</span></div>
            <div className="fd">Одно закреплённое сообщение с актуальным «кто идёт». Бот редактирует его, а не спамит новыми.</div>
          </div>
          <button type="button" className="rd-cl-tgl" role="switch" aria-checked={false} aria-label="Живой закреп (скоро)" disabled />
        </div>
        <div className="rd-cl-feat soon">
          <div className="fi">
            <div className="ft">Строгий режим<span className="rd-cl-soon-tag">скоро</span></div>
            <div className="fd">Должники — только чтение; исключённые из клуба — бан в чате.</div>
          </div>
          <button type="button" className="rd-cl-tgl" role="switch" aria-checked={false} aria-label="Строгий режим (скоро)" disabled />
        </div>
      </div>

      {error && <div className="rd-error">{error}</div>}

      <button type="button" className="rd-btn-outline" onClick={handleRefresh} disabled={busy}>
        {refreshMutation.isPending ? <Spinner size="s" /> : 'Проверить права ещё раз'}
      </button>
      <button
        type="button"
        className="rd-btn-outline"
        style={{ color: 'var(--danger)', marginTop: 8 }}
        onClick={() => { haptic.impact('light'); setShowUnlinkModal(true); }}
        disabled={busy}
      >
        Отвязать чат
      </button>

      <Modal open={showUnlinkModal} onOpenChange={(v) => !unlinkMutation.isPending && setShowUnlinkModal(v)}>
        <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Text weight="2">Отвязать чат «{status.chatTitle ?? 'без названия'}»?</Text>
          <Text>
            Бот выйдет из группы, ссылка-приглашение перестанет работать. Сам чат и его участники не пострадают.
          </Text>
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <Button size="m" stretched mode="outline" onClick={() => setShowUnlinkModal(false)} disabled={unlinkMutation.isPending}>
              Отмена
            </Button>
            <Button size="m" stretched onClick={handleUnlink} disabled={unlinkMutation.isPending}>
              {unlinkMutation.isPending ? <Spinner size="s" /> : 'Отвязать'}
            </Button>
          </div>
        </div>
      </Modal>

      {toast && <Toast message={toast} onClose={() => setToast(null)} />}
    </>
  );
};

export const ClubChatTab: FC<ClubChatTabProps> = ({ clubId }) => {
  const statusQuery = useChatLinkStatusQuery(clubId);
  const status = statusQuery.data;

  if (statusQuery.isPending) {
    return (
      <div className="rd-spinner-row">
        <Spinner size="m" />
      </div>
    );
  }

  if (!status) {
    return <div className="rd-error">Не удалось загрузить статус чата</div>;
  }

  return (
    <>
      <div className="rd-section-sub-h">Телеграм-чат</div>
      {status.linked
        ? <LinkedState clubId={clubId} status={status} />
        : <NotLinkedState startGroupUrl={status.startGroupUrl} />}
    </>
  );
};
