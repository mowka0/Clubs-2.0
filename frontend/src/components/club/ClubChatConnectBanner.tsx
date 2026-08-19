import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useHaptic } from '../../hooks/useHaptic';
import foxChatArt from '../../assets/mascot/fox-chat.png';
import telegramPlaneIcon from '../../assets/icons/telegram-plane.png';

/**
 * Префикс ключа localStorage для отметки «организатор свернул панель».
 * Хранится ПО КЛУБУ: свернув предложение в одном клубе, организатор должен
 * получить его в другом своём клубе.
 *
 * Строка ключа осталась от прежнего смысла («скрыл насовсем») намеренно: у тех, кто уже
 * нажимал «Позже», панель после этой правки не вернётся целиком, а покажется свёрнутой
 * строкой — ровно то поведение, ради которого правка и делается.
 */
const COLLAPSE_KEY_PREFIX = 'clubs:chat-banner-dismissed:';

/** Значение отметки — само по себе смысла не несёт, важно наличие ключа. */
const COLLAPSE_FLAG_VALUE = '1';

/** Ценность подключения чата: три строки, формулировки утверждены PO (мокап variant-c). */
const PERKS: readonly string[] = [
  'Полная синхронизация с клубом: подписка, бейджи, теги, роли и т.д.',
  'Умное голосование и оповещение о событиях.',
  'Автоматическое управление групповыми взносами/сборами.',
];

function collapseKey(clubId: string): string {
  return `${COLLAPSE_KEY_PREFIX}${clubId}`;
}

function isCollapsed(clubId: string): boolean {
  try {
    return localStorage.getItem(collapseKey(clubId)) !== null;
  } catch {
    // localStorage недоступен (приватный режим, веб-вью с отключённым storage) —
    // считаем «не свёрнуто»: панель показать безопаснее, чем потерять единственный заход.
    return false;
  }
}

function rememberCollapsed(clubId: string): void {
  try {
    localStorage.setItem(collapseKey(clubId), COLLAPSE_FLAG_VALUE);
  } catch {
    // Запись не удалась — сворачивание живёт в состоянии компонента до перемонтирования
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
 * «Позже в настройках» **сворачивает** панель в строку, а не убирает её. Прежде отметка
 * скрытия была необратимой: одним тапом закрывалась единственная заметная дверь к
 * подключению чата, и вернуть её из интерфейса было нечем — оставался неочевидный путь
 * «Управление → Чат» (баг PO 2026-08-19). Для продукта, который весь про плагин к чату,
 * терять эту дверь навсегда слишком дорого.
 *
 * Роль-развилка (владелец + чат не привязан) остаётся на вызывающей стороне — как у
 * FoxEmpty; компонент знает только про собственное сворачивание.
 */
export const ClubChatConnectBanner: FC<ClubChatConnectBannerProps> = ({ clubId }) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  // Отметка читается один раз при монтировании. Привязка к клубу — структурная:
  // вызывающая сторона монтирует панель с key={club.id}, поэтому при переходе
  // A → B (страница клуба при этом НЕ перемонтируется) состояние не протекает.
  const [collapsed, setCollapsed] = useState(() => isCollapsed(clubId));

  const handleConnect = () => {
    haptic.impact('medium');
    navigate(`/clubs/${clubId}/manage?tab=chat`);
  };

  const handleCollapse = () => {
    haptic.impact('light');
    setCollapsed(true);
    rememberCollapsed(clubId);
  };

  // Разворачиваем только в состоянии компонента, отметку не стираем: человек уже сказал
  // «потом», и на следующем заходе страница снова должна быть спокойной. Разворот — способ
  // заглянуть в предложение, а не отмена решения.
  const handleExpand = () => {
    haptic.impact('light');
    setCollapsed(false);
  };

  if (collapsed) {
    return (
      <button
        type="button"
        className="rd-chat-panel-row"
        aria-expanded={false}
        onClick={handleExpand}
      >
        <img src={telegramPlaneIcon} alt="" draggable={false} />
        <span className="rd-chat-panel-row-tx">Чат клуба не подключён</span>
        <span className="rd-chat-panel-row-chev" aria-hidden="true">›</span>
      </button>
    );
  }

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
          <button type="button" className="rd-chat-panel-ghost" onClick={handleCollapse}>
            Позже в настройках
          </button>
        </div>
      </div>
    </div>
  );
};
