import { lazy, FC } from 'react';
import { createBrowserRouter, Navigate, useParams } from 'react-router-dom';
import { Layout } from './components/Layout';

// Main tab pages — eagerly imported for instant tab switching
import { DiscoveryPage } from './pages/DiscoveryPage';
import { MyClubsPage } from './pages/MyClubsPage';
import { EventsPage } from './pages/EventsPage';
import { ProfilePage } from './pages/ProfilePage';

// Nested pages — lazy loaded for code splitting
const ClubPage = lazy(() =>
  import('./pages/ClubPage').then((m) => ({ default: m.ClubPage })),
);
const EventPage = lazy(() =>
  import('./pages/EventPage').then((m) => ({ default: m.EventPage })),
);
const InvitePage = lazy(() =>
  import('./pages/InvitePage').then((m) => ({ default: m.InvitePage })),
);
const OrganizerClubManage = lazy(() =>
  import('./pages/OrganizerClubManage').then((m) => ({ default: m.OrganizerClubManage })),
);

// Legacy /clubs/:id/interior was merged into unified /clubs/:id.
// Preserve :id in redirect so old bookmarks / deep-links don't 404.
const InteriorRedirect: FC = () => {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/clubs/${id}`} replace />;
};

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      // Main tab routes
      {
        path: '/',
        element: <DiscoveryPage />,
      },
      {
        path: '/my-clubs',
        element: <MyClubsPage />,
      },
      {
        path: '/events',
        element: <EventsPage />,
      },
      {
        path: '/profile',
        element: <ProfilePage />,
      },

      // Legacy redirect: /organizer was merged into /my-clubs.
      // Kept so old bookmarks / deep-links don't 404.
      {
        path: '/organizer',
        element: <Navigate to="/my-clubs" replace />,
      },

      // Nested routes (lazy loaded, show BackButton)
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
        path: '/clubs/:id/manage',
        element: <OrganizerClubManage />,
      },
      {
        path: '/invite/:code',
        element: <InvitePage />,
      },
    ],
  },
]);
