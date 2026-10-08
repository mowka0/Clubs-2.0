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
      title="Прокачай чат до настоящего клуба"
      description={
        // Обещаем только то, что бот делает: «Напомнить» тем, кто не ответил, жмёт организатор,
        // а должникам бот напоминает сам (DebtScheduler). Опроса «когда удобно» нет — отменён.
        <>
          <span className="rd-connect-line">🗓 Создал встречу — бот позовёт чат, соберёт «иду / не иду» и сам обновит закреп. Молчунам напомнит в одно нажатие.</span>
          <span className="rd-connect-line">💸 Сходили в бар — бот поделит счёт, разошлёт доли, запомнит, кто кому должен, и сам напомнит должникам.</span>
          <span className="rd-connect-line">📈 История встреч и статистика клуба копятся сами — есть что вспомнить.</span>
          <span className="rd-connect-line">Клуб создастся из выбранной группы за минуту, заполнять ничего не нужно.</span>
        </>
      }
      primary={primary}
    />
  );
};
