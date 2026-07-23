import { lazy, FC } from 'react';
import { createBrowserRouter, Navigate, useParams } from 'react-router-dom';
import { Layout } from './components/Layout';

// Страницы основных табов — импортируются сразу, для мгновенного переключения таба
import { DiscoveryPage } from './pages/DiscoveryPage';
import { MyClubsPage } from './pages/MyClubsPage';
import { ActivitiesPage } from './pages/ActivitiesPage';
import { ProfilePage } from './pages/ProfilePage';

// Вложенные страницы — lazy-загрузка для code splitting
const ClubPage = lazy(() =>
  import('./pages/ClubPage').then((m) => ({ default: m.ClubPage })),
);
const EventPage = lazy(() =>
  import('./pages/EventPage').then((m) => ({ default: m.EventPage })),
);
const SkladchinaPage = lazy(() =>
  import('./pages/SkladchinaPage').then((m) => ({ default: m.SkladchinaPage })),
);
const CreateSkladchinaPage = lazy(() =>
  import('./pages/CreateSkladchinaPage').then((m) => ({ default: m.CreateSkladchinaPage })),
);
const CreateSplitBillPage = lazy(() =>
  import('./pages/CreateSplitBillPage').then((m) => ({ default: m.CreateSplitBillPage })),
);
const CreateEventPage = lazy(() =>
  import('./pages/CreateEventPage').then((m) => ({ default: m.CreateEventPage })),
);
const InvitePage = lazy(() =>
  import('./pages/InvitePage').then((m) => ({ default: m.InvitePage })),
);
const OrganizerClubManage = lazy(() =>
  import('./pages/OrganizerClubManage').then((m) => ({ default: m.OrganizerClubManage })),
);
const FeedbackPage = lazy(() =>
  import('./pages/FeedbackPage').then((m) => ({ default: m.FeedbackPage })),
);

// Старый /clubs/:id/interior объединён в единый /clubs/:id.
// Сохраняем :id в редиректе, чтобы старые закладки / deep-link'и не давали 404.
const InteriorRedirect: FC = () => {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/clubs/${id}`} replace />;
};

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      // Маршруты основных табов
      {
        path: '/',
        element: <DiscoveryPage />,
      },
      {
        path: '/my-clubs',
        element: <MyClubsPage />,
      },
      {
        path: '/activities',
        element: <ActivitiesPage />,
      },
      {
        path: '/events',
        element: <ActivitiesPage />,
      },
      {
        path: '/skladchina',
        element: <ActivitiesPage />,
      },
      {
        path: '/profile',
        element: <ProfilePage />,
      },

      // Старый редирект: /organizer объединён в /my-clubs.
      // Оставлен, чтобы старые закладки / deep-link'и не давали 404.
      {
        path: '/organizer',
        element: <Navigate to="/my-clubs" replace />,
      },

      // Вложенные маршруты (lazy-загрузка, показывают BackButton)
      {
        path: '/clubs/:id',
        element: <ClubPage />,
      },
      {
        path: '/clubs/:id/interior',
        element: <InteriorRedirect />,
      },
      {
        path: '/events/:id',
        element: <EventPage />,
      },
      {
        path: '/skladchina/:id',
        element: <SkladchinaPage />,
      },
      {
        path: '/clubs/:id/manage',
        element: <OrganizerClubManage />,
      },
      {
        path: '/clubs/:id/events/new',
        element: <CreateEventPage />,
      },
      {
        path: '/clubs/:id/skladchina/new',
        element: <CreateSkladchinaPage />,
      },
      {
        path: '/clubs/:id/skladchina/split',
        element: <CreateSplitBillPage />,
      },
      {
        path: '/invite/:code',
        element: <InvitePage />,
      },
      {
        // Форма обратной связи — открывается из шита «+» («Сообщить о проблеме»).
        path: '/feedback',
        element: <FeedbackPage />,
      },
    ],
  },
]);
