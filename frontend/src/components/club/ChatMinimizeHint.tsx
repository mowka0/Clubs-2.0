import { FC } from 'react';
import { createPortal } from 'react-dom';
import { closeMiniApp } from '../../telegram/miniApp';

/**
 * Подсказка «вы уже в этом чате», которую пилюля «💬 В чат» показывает вместо перехода,
 * когда чат клуба лежит прямо под приложением (см. `telegram/chatOrigin`).
 *
 * Живёт в правом верхнем углу — вплотную к контролам Telegram, потому что читать текст и
 * искать кнопку человек должен в одном месте: хвостик подсказки указывает прямо в кнопку
 * сворачивания, а пульсирующее кольцо её подсвечивает (выбор PO из трёх вариантов,
 * `docs/design/chat-button-minimize-hint/`).
 *
 * Две кнопки — это два разных выхода, а не дубль: «Выйти в чат» закрывает приложение (быстро,
 * но состояние теряется), кнопка сворачивания Telegram оставляет его в плашке. Свернуть кодом
 * нельзя, поэтому выбор за человеком.
 *
 * Портал в body — иначе `position: fixed` считался бы от ближайшего предка с transform/filter,
 * а пилюля живёт внутри карточки «О клубе» с backdrop-filter.
 */
export const ChatMinimizeHint: FC<{ onClose: () => void }> = ({ onClose }) =>
  createPortal(
    // Завеса перехватывает тап мимо подсказки — на телефоне это привычный способ её закрыть.
    <div className="rd-mzhint-veil" onClick={onClose}>
      <div className="rd-mzhint-halo" aria-hidden="true" />
      {/* Без role="dialog": глобальное правило brand-theme.css прибивает всё с этой ролью
          к низу экрана через !important (там живут боттом-шиты). */}
      <div className="rd-mzhint" onClick={(e) => e.stopPropagation()}>
        <b className="rd-mzhint-h">Вы уже в этом чате</b>
        <p className="rd-mzhint-tx">
          Приложение открыто поверх него — сверните вот этой кнопкой, и оно останется под рукой.
        </p>
        <button type="button" className="rd-mzhint-cta" onClick={closeMiniApp}>
          Выйти в чат
        </button>
        <button type="button" className="rd-mzhint-ghost" onClick={onClose}>
          Понятно
        </button>
      </div>
    </div>,
    document.body,
  );
