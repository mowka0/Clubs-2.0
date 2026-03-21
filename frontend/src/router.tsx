import { lazy } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { Layout } from './components/Layout';

// Main tab pages — eagerly imported for instant tab switching
import { DiscoveryPage } from './pages/DiscoveryPage';
import { MyClubsPage } from './pages/MyClubsPage';
import { OrganizerPage } from './pages/OrganizerPage';
import { ProfilePage } from './pages/ProfilePage';

// Nested pages — lazy loaded for code splitting
const ClubPage = lazy(() =>
  import('./pages/ClubPage').then((m) => ({ default: m.ClubPage })),
);
const ClubInteriorPage = lazy(() =>
  import('./pages/ClubInteriorPage').then((m) => ({
    default: m.ClubInteriorPage,
  })),
);
const EventPage = lazy(() =>
  import('./pages/EventPage').then((m) => ({ default: m.EventPage })),
);
const InvitePage = lazy(() =>
  import('./pages/InvitePage').then((m) => ({ default: m.InvitePage })),
);

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
        path: '/organizer',
        element: <OrganizerPage />,
      },
      {
        path: '/profile',
        element: <ProfilePage />,
      },

      // Nested routes (lazy loaded, show BackButton)
      {
        path: '/clubs/:id',
        element: <ClubPage />,
      },
      {
        path: '/clubs/:id/interior',
        element: <ClubInteriorPage />,
      },
      {
        path: '/events/:id',
        element: <EventPage />,
      },
      {
        path: '/invite/:code',
        element: <InvitePage />,
      },
    ],
  },
]);
