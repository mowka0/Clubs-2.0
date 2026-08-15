import { FC, useState } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { openTmeLink } from '../../utils/telegramLinks';
import { isClubChatUnderApp } from '../../telegram/chatOrigin';
import { ChatMinimizeHint } from './ChatMinimizeHint';

/**
 * Пилюля «В чат» под описанием клуба. Два режима, и они принципиально разные:
 *
 * - `open` — участник: тап открывает чат в Telegram;
 * - `hint` — гость: двери в чат у него ещё нет, поэтому тап показывает подсказку с кнопкой,
 *   которая ведёт к вступлению. Молча открывать чат нельзя (бот его не впустит), а прятать
 *   пилюлю — врать: чат у клуба есть, и это довод вступить.
 */
export type ClubChatPillProps =
  | { mode: 'open'; clubId: string; inviteLink: string }
  | { mode: 'hint'; hintText: string; ctaLabel: string; onCta: () => void };

export const ClubChatPill: FC<ClubChatPillProps> = (props) => {
  const haptic = useHaptic();
  const [hintOpen, setHintOpen] = useState(false);
  const [minimizeHintOpen, setMinimizeHintOpen] = useState(false);

  const handleClick = () => {
    haptic.impact('light');
    if (props.mode === 'open') {
      // Чат этого клуба уже лежит под приложением — переходить некуда, объясняем как выйти
      // к нему (chatOrigin). На чат любого другого клуба уводим ссылкой, как обычно.
      if (isClubChatUnderApp(props.clubId)) {
        setMinimizeHintOpen(true);
        return;
      }
      openTmeLink(props.inviteLink);
      return;
    }
    setHintOpen((shown) => !shown);
  };

  return (
    <div className="rd-club-chatrow">
      <button
        type="button"
        className="rd-club-chatpill"
        onClick={handleClick}
        aria-expanded={props.mode === 'hint' ? hintOpen : undefined}
      >
        <span aria-hidden="true">💬</span>
        В чат
      </button>

      {minimizeHintOpen && <ChatMinimizeHint onClose={() => setMinimizeHintOpen(false)} />}

      {props.mode === 'hint' && hintOpen && (
        <>
          {/* Завеса на весь экран: тап мимо подсказки закрывает её — на телефоне это
              единственный привычный способ выйти, клавиши Esc там нет. */}
          <div className="rd-chathint-veil" onClick={() => setHintOpen(false)} />
          {/* Без role="dialog": глобальное правило brand-theme.css прибивает всё с этой
              ролью к низу экрана через !important (там живут боттом-шиты). Раскрытие
              и так объявлено через aria-expanded на самой пилюле. */}
          <div className="rd-chathint">
            <div className="rd-chathint-tx">{props.hintText}</div>
            <button
              type="button"
              className="rd-chathint-cta"
              onClick={() => {
                setHintOpen(false);
                props.onCta();
              }}
            >
              {props.ctaLabel}
            </button>
          </div>
        </>
      )}
    </div>
  );
};
