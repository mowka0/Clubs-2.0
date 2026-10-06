import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../mocks/server';
import { renderWithProviders } from '../utils/renderWithProviders';
import { NewClubFromChatGate } from '../../components/club/NewClubFromChatGate';
import { rememberNewClubLinkingStarted } from '../../utils/chatLinkPending';

const KNOWN_CLUB_ID = '00000000-0000-0000-0000-00000000aaaa';
const NEW_CLUB_ID = '00000000-0000-0000-0000-00000000bbbb';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => localStorage.clear());

const LocationProbe = () => {
  const location = useLocation();
  return <div>{`at:${location.pathname}${location.search}`}</div>;
};

function mockMyClubs(clubIds: string[]) {
  server.use(
    http.get('*/api/users/me/clubs', () => HttpResponse.json(clubIds.map((clubId, i) => ({
      id: `mem-${i}`, userId: 'user-1', clubId, status: 'active', role: 'organizer',
      joinedAt: '2026-10-06T00:00:00Z', subscriptionExpiresAt: null,
    })))),
  );
}

function renderGate(startPath: string) {
  return renderWithProviders(
    <>
      <NewClubFromChatGate />
      <Routes>
        <Route path="*" element={<LocationProbe />} />
      </Routes>
    </>,
    { routerEntries: [startPath] },
  );
}

describe('NewClubFromChatGate', () => {
  it('вернулся после создания клуба из чата — на страницу клуба со шторкой «Клуб создан»', async () => {
    rememberNewClubLinkingStarted([KNOWN_CLUB_ID], Date.now());
    mockMyClubs([KNOWN_CLUB_ID, NEW_CLUB_ID]);

    // Открыл кнопкой из лички: адрес уже с параметром — гейт не должен его потерять.
    renderGate(`/clubs/${NEW_CLUB_ID}?created=1`);

    // Ждём, пока гейт отработает (он стирает отметку), — иначе адрес совпал бы ещё до перехода.
    await waitFor(() => expect(localStorage.getItem('clubs:chat-linking-pending')).toBeNull());
    expect(screen.getByText(`at:/clubs/${NEW_CLUB_ID}?created=1`)).toBeInTheDocument();
  });

  it('открыл приложение сам, не через личку — тоже попадает на шторку', async () => {
    rememberNewClubLinkingStarted([KNOWN_CLUB_ID], Date.now());
    mockMyClubs([KNOWN_CLUB_ID, NEW_CLUB_ID]);

    renderGate('/');

    expect(await screen.findByText(`at:/clubs/${NEW_CLUB_ID}?created=1`)).toBeInTheDocument();
  });

  it('никто не уходил создавать клуб — гейт никуда не ведёт', async () => {
    mockMyClubs([KNOWN_CLUB_ID, NEW_CLUB_ID]);

    renderGate('/my-clubs');

    expect(await screen.findByText('at:/my-clubs')).toBeInTheDocument();
  });
});
