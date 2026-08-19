import { FC } from 'react';
import { Navigate } from 'react-router-dom';
import { DiscoveryPage } from '../pages/DiscoveryPage';
import { ConnectChatScreen } from './ConnectChatScreen';
import { PageFallback } from './Layout';
import { PRODUCT_PROFILE } from '../config/productProfile';
import { useMyClubsQuery } from '../queries/clubs';

/**
 * Корневой роут «/». В чат-модели первый экран отвечает на вопрос «что у меня
 * происходит», а не «какие вообще бывают клубы», поэтому человек попадает в свой
 * клуб, а не в каталог чужих.
 *
 * Ветки:
 * - профиль «каталог» — прежнее поведение, витрина Discovery;
 * - клубы ещё грузятся — спиннер, чтобы не мигнуть каталогом и не увести не туда;
 * - ошибка загрузки — «Мои клубы»: там есть готовый экран ошибки с повтором,
 *   а каталог в этот момент соврал бы, что клубов нет;
 * - ноль клубов — предложение подключить чат: в чат-модели это и есть начало пути,
 *   а каталог чужих клубов отвечал бы не на тот вопрос;
 * - ровно один клуб — сразу в него, даже если он ещё не наполнен: мастер открывается кнопкой
 *   с самой страницы, а не подсовывается при каждом запуске (решение PO 2026-08-17);
 * - несколько — список «Мои клубы».
 */
export const HomeRoute: FC = () => {
  const myClubsQuery = useMyClubsQuery();

  if (PRODUCT_PROFILE.homeTarget === 'catalog') {
    return <DiscoveryPage />;
  }

  if (myClubsQuery.isPending) {
    return <PageFallback />;
  }

  if (myClubsQuery.isError) {
    return <Navigate to="/my-clubs" replace />;
  }

  const myClubs = myClubsQuery.data ?? [];

  if (myClubs.length === 0) {
    return <ConnectChatScreen />;
  }

  if (myClubs.length === 1) {
    return <Navigate to={`/clubs/${myClubs[0].clubId}`} replace />;
  }

  return <Navigate to="/my-clubs" replace />;
};
