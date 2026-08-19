import { FC } from 'react';
import { FoxEmpty } from './feed/FoxEmpty';
import foxChatArt from '../assets/mascot/fox-chat.png';
import { useHaptic } from '../hooks/useHaptic';
import { useNewClubChatLinkQuery, useStartChatLinkingMutation } from '../queries/chatLink';

/**
 * Первый экран человека без клубов в чат-модели: предложение подключить свой телеграм-чат.
 *
 * Кнопка уводит в Telegram по ссылке `?startgroup=new` — дальше список групп показывает сам
 * Telegram, а клуб создаётся из выбранного чата (см. ClubsBot). Формы создания клуба человек
 * не видит вовсе: название берётся у чата, остальное уточняется потом.
 *
 * Возвращается человек сам, когда захочет: Telegram не перебрасывает обратно в Mini App.
 * Появившийся клуб подхватывает HomeRoute при следующем открытии приложения.
 */
export const ConnectChatScreen: FC = () => (
  <div className="rd-page">
    <ConnectChatEmpty />
  </div>
);

/**
 * Та же сцена без обёртки страницы — для встраивания в уже существующий экран
 * (пустое состояние «Мои клубы»), где свой `rd-page` уже есть.
 */
export const ConnectChatEmpty: FC = () => {
  const haptic = useHaptic();
  const { data } = useNewClubChatLinkQuery();
  const startLinking = useStartChatLinkingMutation();

  // Кнопку показываем только с готовой ссылкой: тап «в пустоту» на первом же экране
  // выглядел бы как сломанный продукт. Запрос кэшируется навсегда, пауза почти незаметна.
  const primary = data
    ? {
        label: 'Выбрать чат',
        onClick: () => {
          haptic.impact('light');
          startLinking.mutate({ clubId: null, startGroupUrl: data.startGroupUrl });
        },
      }
    : undefined;

  return (
    <FoxEmpty
      art={foxChatArt}
      artLabel="Лис у телефона с чатом"
      title="Бот ведёт встречи в вашем чате"
      description={
        'Опрос «когда удобно», сбор «иду / не иду», напоминания и итог явки — ' +
        'прямо в чате. Клуб создастся сам из выбранной группы, заполнять ничего не нужно.'
      }
      primary={primary}
    />
  );
};
