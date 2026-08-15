import { FC } from 'react';
import { createPortal } from 'react-dom';

interface ChatMinimizeHintProps {
  /** Заголовок: что именно произошло — у пилюли «В чат» и у «назад» поводы разные. */
  title: string;
  /** Пояснение под заголовком; заканчивается указанием свернуть приложение. */
  text: string;
  onClose: () => void;
}

/**
 * Подсказка «чат прямо под приложением» — общая для двух тупиков, в которые упирается
 * человек, открывший приложение кнопкой из чата клуба (см. `telegram/chatOrigin`):
 *
 * - пилюля «💬 В чат» — переходить некуда, чат уже под нами;
 * - кнопка «назад» на первом же экране — внутри приложения истории нет, а вернуться человек
 *   хочет именно в чат.
 *
 * Живёт в правом верхнем углу — вплотную к контролам Telegram, потому что читать текст и
 * искать кнопку человек должен в одном месте: хвостик подсказки указывает прямо в кнопку
 * сворачивания (выбор PO из трёх вариантов, `docs/design/chat-button-minimize-hint/`;
 * подсвечивающее кольцо вокруг кнопки было и убрано по его же просьбе — хвостика достаточно).
 *
 * Своей кнопки «выйти в чат» здесь намеренно нет: закрыть Mini App кодом можно, а свернуть —
 * нельзя (среди методов Mini Apps только `web_app_close`), и закрытие теряет состояние.
 * Подсказка объясняет и уступает действие кнопке Telegram (решение PO 2026-08-15).
 *
 * Портал в body — иначе `position: fixed` считался бы от ближайшего предка с transform/filter,
 * а пилюля живёт внутри карточки «О клубе» с backdrop-filter.
 */
export const ChatMinimizeHint: FC<ChatMinimizeHintProps> = ({ title, text, onClose }) =>
  createPortal(
    // Завеса перехватывает тап мимо подсказки — на телефоне это привычный способ её закрыть.
    <div className="rd-mzhint-veil" onClick={onClose}>
      {/* Без role="dialog": глобальное правило brand-theme.css прибивает всё с этой ролью
          к низу экрана через !important (там живут боттом-шиты). */}
      <div className="rd-mzhint" onClick={(e) => e.stopPropagation()}>
        <b className="rd-mzhint-h">{title}</b>
        <p className="rd-mzhint-tx">{text}</p>
        <button type="button" className="rd-mzhint-cta" onClick={onClose}>
          Понятно
        </button>
      </div>
    </div>,
    document.body,
  );
