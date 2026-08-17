import { FC } from 'react';
import { Navigate } from 'react-router-dom';
import { DiscoveryPage } from '../pages/DiscoveryPage';
import { ConnectChatScreen } from './ConnectChatScreen';
import { PageFallback } from './Layout';
import { PRODUCT_PROFILE } from '../config/productProfile';
import { isActiveManagerMembership } from '../utils/membershipRole';
import { useClubQuery, useMyClubsQuery } from '../queries/clubs';

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
 * - ровно один клуб — сразу в него; если он ещё не наполнен (нет города) и человек им
 *   управляет — сперва в мастер наполнения;
 * - несколько — список «Мои клубы».
 */
export const HomeRoute: FC = () => {
  const myClubsQuery = useMyClubsQuery();

  // Единственный клуб читаем всегда (хук не может стоять после return): у клуба, только что
  // родившегося из чата, ещё нет города — его спрашивает ChatConnectedScreen.
  const onlyMembership = myClubsQuery.data?.length === 1 ? myClubsQuery.data[0] : undefined;
  const onlyClubQuery = useClubQuery(onlyMembership?.clubId);

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

  if (onlyMembership) {
    if (onlyClubQuery.isPending) {
      return <PageFallback />;
    }
    const club = onlyClubQuery.data;
    // Город спрашиваем сразу после подключения чата и только у того, кто может его сохранить:
    // рядовой участник упёрся бы в 403 и остался на экране без выхода.
    // Клуб из чата ещё не наполнен — ведём в мастер, а не на пустую страницу.
    if (club && club.cityId === null && isActiveManagerMembership(onlyMembership)) {
      return <Navigate to={`/clubs/${club.id}/setup`} replace />;
    }
    return <Navigate to={`/clubs/${onlyMembership.clubId}`} replace />;
  }

  return <Navigate to="/my-clubs" replace />;
};
