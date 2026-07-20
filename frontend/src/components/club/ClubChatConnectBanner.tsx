import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../../hooks/useHaptic';
import foxChatArt from '../../assets/mascot/fox-chat.png';
import telegramPlaneIcon from '../../assets/icons/telegram-plane.png';

/**
 * Префикс ключа localStorage для отметки «организатор скрыл панель».
 * Хранится ПО КЛУБУ: отложив подключение в одном клубе, организатор должен
 * получить предложение в другом своём клубе.
 */
const DISMISS_KEY_PREFIX = 'clubs:chat-banner-dismissed:';

/** Значение отметки скрытия — само по себе смысла не несёт, важно наличие ключа. */
const DISMISS_FLAG_VALUE = '1';

/** Ценность подключения чата: три строки, формулировки утверждены PO (мокап variant-c). */
const PERKS: readonly string[] = [
  'Полная синхронизация с клубом: подписка, бейджи, теги, роли и т.д.',
  'Умное голосование и оповещение о событиях.',
  'Автоматическое управление групповыми взносами/сборами.',
];

function dismissKey(clubId: string): string {
  return `${DISMISS_KEY_PREFIX}${clubId}`;
}

function isDismissed(clubId: string): boolean {
  try {
    return localStorage.getItem(dismissKey(clubId)) !== null;
  } catch {
    // localStorage недоступен (приватный режим, веб-вью с отключённым storage) —
    // считаем «не скрыто»: панель показать безопаснее, чем потерять единственный заход.
    return false;
  }
}

function rememberDismissed(clubId: string): void {
  try {
    localStorage.setItem(dismissKey(clubId), DISMISS_FLAG_VALUE);
  } catch {
    // Запись не удалась — скрытие живёт в состоянии компонента до перемонтирования
    // страницы. Осознанная деградация: ошибку пользователю не показываем.
  }
}

interface ClubChatConnectBannerProps {
  /** Клуб, для которого предлагается привязка чата; он же — ключ отметки скрытия. */
  clubId: string;
}

/**
 * Панель подключения чата на странице клуба (club-chat-link, дизайн PO 2026-07-20).
 *
 * Второй, ненавязчивый заход к организатору, который на экране «Клуб создан» нажал
 * «Позже»: объясняет ценность тремя строками и ведёт в тот же таб «Чат» в управлении.
 *
 * Роль-развилка (владелец + чат не привязан) остаётся на вызывающей стороне — как у
 * FoxEmpty; компонент знает только про собственное скрытие «Позже в настройках».
 */
export const ClubChatConnectBanner: FC<ClubChatConnectBannerProps> = ({ clubId }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  // Отметка читается один раз при монтировании. Привязка к клубу — структурная:
  // вызывающая сторона монтирует панель с key={club.id}, поэтому при переходе
  // A → B (страница клуба при этом НЕ перемонтируется) состояние не протекает.
  const [dismissed, setDismissed] = useState(() => isDismissed(clubId));

  if (dismissed) return null;

  const handleConnect = () => {
    haptic.impact('medium');
    navigate(`/clubs/${clubId}/manage?tab=chat`);
  };

  const handleDismiss = () => {
    haptic.impact('light');
    setDismissed(true);
    rememberDismissed(clubId);
  };

  return (
    <div className="rd-glass rd-chat-panel">
      <div className="rd-chat-panel-fox">
        <img src={foxChatArt} alt="Лис-маскот" draggable={false} />
      </div>
      <div className="rd-chat-panel-body">
        <div className="rd-chat-panel-ttl">
          <img className="rd-chat-panel-tg" src={telegramPlaneIcon} alt="" draggable={false} />
          <span>Подключи чат клуба</span>
        </div>
        <ul className="rd-chat-panel-perks" role="list">
          {PERKS.map((perk) => (
            <li className="rd-chat-panel-li" key={perk}>
              <span className="rd-chat-panel-dot" aria-hidden="true">•</span>
              <span>{perk}</span>
            </li>
          ))}
        </ul>
        <div className="rd-chat-panel-acts">
          <button type="button" className="rd-btn-primary" onClick={handleConnect}>
            Подключить
          </button>
          <button type="button" className="rd-chat-panel-ghost" onClick={handleDismiss}>
            Позже в настройках
          </button>
        </div>
      </div>
    </div>
  );
};
