import { FC, useState } from 'react';
import { Button, Modal, Spinner, Text } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  useChatLinkStatusQuery,
  usePinClubLinkMutation,
  useRefreshChatLinkMutation,
  useStartChatLinkingMutation,
  useUnlinkChatMutation,
  useUpdateChatLinkMutation,
} from '../../queries/chatLink';
import { rememberChatLinkingStarted } from '../../utils/chatLinkPending';
import { Toast } from '../Toast';
import type { ChatLinkStatusDto, UpdateChatLinkRequest } from '../../types/api';

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

const NotLinkedState: FC<{ clubId: string; startGroupUrl: string }> = ({ clubId, startGroupUrl }) => {
  const haptic = useHaptic();
  const startLinking = useStartChatLinkingMutation();
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
          <span className="t"><b>Живой закреп</b> — в шапке чата всегда актуальное «кто идёт» по ближайшей встрече</span>
        </div>
        <div className="rd-cl-step">
          <span className="n">3</span>
          <span className="t"><b>Теги наград</b> — награды участников видны в чате тегами рядом с именами</span>
        </div>
        <div className="rd-cl-step">
          <span className="n">4</span>
          <span className="t"><b>Строгий режим</b> — должники читают, но не пишут; покинувшие клуб уходят и из чата</span>
        </div>
      </div>
      <button
        type="button"
        className="rd-btn-primary"
        onClick={() => {
          haptic.impact('medium');
          // Отсюда человек уходит в Telegram (на iOS — вместе с закрытием Mini App); отметка
          // нужна, чтобы по возвращении показать окно со статусом подключения бота.
          rememberChatLinkingStarted(clubId, Date.now());
          // Намерение отмечается на сервере: бот узнаёт о добавлении из my_chat_member, где
          // payload ссылки отсутствует, и без отметки завёл бы новый клуб вместо привязки.
          startLinking.mutate({ clubId, startGroupUrl });
        }}
      >
        Привязать чат
      </button>
      <div className="rd-cta-hint" style={{ marginTop: 8 }}>
        Откроется Telegram — выберите группу клуба.<br />
        Бот попросит права администратора: закреплять сообщения, приглашать участников, снимать блокировки и управлять тегами.
      </div>
    </div>
  );
};

// ---- Состояния B/C: привязан ----

const LinkedState: FC<{ clubId: string; status: ChatLinkStatusDto }> = ({ clubId, status }) => {
  const haptic = useHaptic();
  const startLinking = useStartChatLinkingMutation();
  const refreshMutation = useRefreshChatLinkMutation(clubId);
  const updateMutation = useUpdateChatLinkMutation(clubId);
  const unlinkMutation = useUnlinkChatMutation(clubId);
  const pinClubLinkMutation = usePinClubLinkMutation(clubId);

  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [showUnlinkModal, setShowUnlinkModal] = useState(false);

  const botInChat = isBotInChat(status);
  const busy = refreshMutation.isPending || updateMutation.isPending || unlinkMutation.isPending
    || pinClubLinkMutation.isPending;

  // Настройку меняет владелец в самом Telegram — мы можем только прочитать её и подсказать.
  const historyHidden = status.historyVisibleToNewMembers === false;

  const handlePinClubLink = () => {
    haptic.impact('medium');
    setError(null);
    pinClubLinkMutation.mutate(undefined, {
      onSuccess: () => { haptic.notify('success'); setToast('Ссылка на клуб закреплена в чате'); },
      onError: (e) => { setError(e.message); haptic.notify('error'); },
    });
  };

  // Алерт деградации: что именно сломалось и как починить (мокап 01-C).
  const alert = !botInChat
    ? { title: 'Бот удалён из чата', sub: 'Привязка сохранена: верните бота в группу и нажмите «Проверить права ещё раз» — всё оживёт само.' }
    : status.doorEnabled && !status.canInviteUsers
      ? { title: 'Бот потерял право приглашать участников', sub: 'Вход через заявки остановлен. Верните боту право «Приглашение участников» в настройках группы — и всё оживёт.' }
      : status.livePinEnabled && !status.canPinMessages
        ? { title: 'Бот потерял право закреплять сообщения', sub: 'Уже созданные статусы бот продолжит обновлять, но закрепить новые не сможет. Верните боту право «Закрепление сообщений» в настройках группы.' }
        : status.strictModeEnabled && !status.canRestrictMembers
          ? { title: 'Бот потерял право блокировать участников', sub: 'Строгий режим остановлен: бот не может ограничивать должников и банить покинувших клуб. Верните боту право «Блокировка пользователей» в настройках группы.' }
          : status.awardTagsEnabled && !status.canManageTags
            ? { title: 'Бот потерял право управлять тегами', sub: 'Теги наград остановлены: бот не может выставлять и менять теги участников. Верните боту право «Управление тегами» в настройках группы.' }
            : null;

  // Один PATCH-хендлер на все тумблеры фич (дверь / живой закреп / статус сборов):
  // частичный запрос содержит ровно одно переключаемое поле.
  const makeToggle = (patch: () => UpdateChatLinkRequest) => () => {
    if (busy) return;
    setError(null);
    haptic.impact('medium');
    updateMutation.mutate(patch(), {
      onSuccess: () => haptic.notify('success'),
      onError: (e) => { setError(e.message); haptic.notify('error'); },
    });
  };

  const handleToggleDoor = makeToggle(() => ({ doorEnabled: !status.doorEnabled }));
  const handleToggleLivePin = makeToggle(() => ({ livePinEnabled: !status.livePinEnabled }));
  const handleToggleSkladchinaStatus = makeToggle(
    () => ({ skladchinaStatusEnabled: !status.skladchinaStatusEnabled }),
  );
  const handleToggleStrictMode = makeToggle(() => ({ strictModeEnabled: !status.strictModeEnabled }));
  const handleToggleAwardTags = makeToggle(() => ({ awardTagsEnabled: !status.awardTagsEnabled }));

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
          onClick={() => {
            haptic.impact('medium');
            rememberChatLinkingStarted(clubId, Date.now());
            startLinking.mutate({ clubId, startGroupUrl: status.startGroupUrl });
          }}
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
          <span className={`rd-cl-pill ${status.canRestrictMembers ? 'ok' : 'bad'}`}>{status.canRestrictMembers ? '✓ блокировки разрешены' : '✕ блокировки запрещены'}</span>
          <span className={`rd-cl-pill ${status.canManageTags ? 'ok' : 'bad'}`}>{status.canManageTags ? '✓ теги разрешены' : '✕ теги запрещены'}</span>
        </div>
        {/* Скрытая история — причина «новички не видят закреп встречи»: Telegram прячет от них
            ВСЁ, что было до вступления. Бот переключить это не может (в Bot API нет метода),
            поэтому единственное, что мы делаем — показываем владельцу, что и где включить. */}
        {historyHidden && (
          <div className="rd-cl-hist">
            <div className="rd-cl-hist-t">Новые участники не видят историю чата</div>
            <div className="rd-cl-hist-d">
              Telegram скрывает от них всё, что было до вступления, — включая закреплённые
              сообщения о встречах и ссылку на клуб. Включается в самом Telegram:
              <b> Управление группой → История чата для новых участников</b>.
              После этого нажмите «Проверить права ещё раз».
            </div>
          </div>
        )}

        {/* Закреп ссылки на клуб: при привязке бот делает его сам, здесь — для чатов,
            привязанных раньше, и для случая «организатор снял закреп вручную». */}
        <div className="rd-cl-pinrow">
          <div className="rd-cl-pin-tx">
            {status.clubLinkPinned
              ? 'Ссылка на клуб закреплена в чате.'
              : 'Закрепите ссылку на клуб, чтобы участники не искали приложение в переписке.'}
          </div>
          <button
            type="button"
            className="rd-cl-copy"
            disabled={busy || !botInChat || !status.canPinMessages}
            onClick={handlePinClubLink}
          >
            {pinClubLinkMutation.isPending
              ? <Spinner size="s" />
              : (status.clubLinkPinned ? 'Закрепить заново' : 'Закрепить')}
          </button>
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

      {/* Тумблеры фич: дверь, живой закреп, статус сборов, строгий режим */}
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
        <div className="rd-cl-feat">
          <div className="fi">
            <div className="ft">Живой закреп</div>
            <div className="fd">
              Одно закреплённое сообщение с актуальным «кто идёт» по каждой будущей встрече.
              Бот редактирует его, а не спамит новыми, а после встречи публикует итог.
            </div>
          </div>
          <button
            type="button"
            className={`rd-cl-tgl${status.livePinEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={status.livePinEnabled}
            aria-label="Живой закреп"
            disabled={busy || (!status.livePinEnabled && (!botInChat || !status.canPinMessages))}
            onClick={handleToggleLivePin}
          />
        </div>
        {/* Статус сборов не требует прав администратора (посты и упоминания доступны
            любому участнику чата) — гейт только «бот в чате». Пин — best-effort. */}
        <div className="rd-cl-feat">
          <div className="fi">
            <div className="ft">Статус сборов в чате</div>
            <div className="fd">
              Живой пост по каждому сбору: «скинулись N из M», дедлайн и кто ещё не ответил
              (с упоминаниями). Напоминание о дедлайне придёт в чат вместо личных сообщений.
            </div>
          </div>
          <button
            type="button"
            className={`rd-cl-tgl${status.skladchinaStatusEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={status.skladchinaStatusEnabled}
            aria-label="Статус сборов в чате"
            disabled={busy || (!status.skladchinaStatusEnabled && !botInChat)}
            onClick={handleToggleSkladchinaStatus}
          />
        </div>
        {/* Теги наград (слайс 4, Bot API 9.5): последняя награда — тег рядом с именем.
            Включение требует права «Управление тегами»; дальше теги держит шедулер синхронизации. */}
        <div className="rd-cl-feat">
          <div className="fi">
            <div className="ft">Теги наград</div>
            <div className="fd">
              Последняя награда участника видна в чате тегом рядом с именем. Работает в обе
              стороны: тег, поставленный в чате руками, станет наградой в приложении.
              {!status.canManageTags && (
                <>
                  {' '}
                  {/* Единственное право, которого нет в экране добавления бота: Telegram не отдаёт
                      его ни по ссылке, ни через дефолтные права (проверено 2026-08-19). */}
                  <b>Боту нужно право «Управление тегами»</b> — включается только руками: профиль
                  бота в группе → «Изменить права» → «Управление тегами», затем «Проверить права
                  ещё раз» выше.
                </>
              )}
            </div>
          </div>
          <button
            type="button"
            className={`rd-cl-tgl${status.awardTagsEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={status.awardTagsEnabled}
            aria-label="Теги наград"
            disabled={busy || (!status.awardTagsEnabled && (!botInChat || !status.canManageTags))}
            onClick={handleToggleAwardTags}
          />
        </div>
        {/* Строгий режим (слайс 5): включение требует права «Блокировка пользователей»,
            сразу мьютит текущих должников (backfill на бэке). */}
        <div className="rd-cl-feat">
          <div className="fi">
            <div className="ft">Строгий режим</div>
            <div className="fd">
              Должники — только чтение до оплаты взноса; покинувшие клуб — бан в чате.
              Возврат в клуб автоматически снимает бан.
            </div>
          </div>
          <button
            type="button"
            className={`rd-cl-tgl${status.strictModeEnabled ? ' on' : ''}`}
            role="switch"
            aria-checked={status.strictModeEnabled}
            aria-label="Строгий режим"
            disabled={busy || (!status.strictModeEnabled && (!botInChat || !status.canRestrictMembers))}
            onClick={handleToggleStrictMode}
          />
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
        : <NotLinkedState clubId={clubId} startGroupUrl={status.startGroupUrl} />}
    </>
  );
};
