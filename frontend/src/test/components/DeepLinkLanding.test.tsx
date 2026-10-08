import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

/**
 * Баг PO 2026-10-08 целиком, с настоящими DeepLinkHandler и HomeRoute: новичок без клубов
 * открывает приложение кнопкой «Открыть клуб» из чата. Оба срабатывают в первом же кадре, и
 * переход «/» в «Мои клубы» раньше побеждал переход по ссылке.
 */

const CLUB = '11111111-2222-3333-4444-555555555555';
const myClubs = { isPending: false, isError: false, data: [] as unknown[] };

vi.mock('../../telegram/sdk', () => ({ getStartParam: () => `club_${CLUB}` }));
vi.mock('../../telegram/chatOrigin', () => ({ rememberDeepLinkLanding: vi.fn() }));
vi.mock('../../queries/clubs', () => ({ useMyClubsQuery: () => myClubs }));
vi.mock('../../components/Layout', () => ({ PageFallback: () => <div>загрузка</div> }));
vi.mock('../../pages/DiscoveryPage', () => ({ DiscoveryPage: () => <div>каталог</div> }));

import { DeepLinkHandler, resetDeepLinkForTests } from '../../components/DeepLinkHandler';
import { HomeRoute } from '../../components/HomeRoute';

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <DeepLinkHandler />
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route path="/my-clubs" element={<div>мои клубы</div>} />
        <Route path="/clubs/:id/join" element={<div>приглашение</div>} />
        <Route path="/clubs/:id" element={<div>клуб</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  resetDeepLinkForTests();
  myClubs.isError = false;
  myClubs.data = [];
});

describe('DeepLinkHandler + HomeRoute — первый кадр после входа по ссылке', () => {
  it('новичок без клубов попадает на экран приглашения, а не в «Мои клубы»', async () => {
    renderApp();
    expect(await screen.findByText('приглашение')).toBeInTheDocument();
    expect(screen.queryByText('мои клубы')).toBeNull();
  });

  it('участник — сразу в клуб', async () => {
    myClubs.data = [{ clubId: CLUB, status: 'active' }];
    renderApp();
    expect(await screen.findByText('клуб')).toBeInTheDocument();
  });

  it('клубы не загрузились — открываем клуб, его страница сама предложит вступить', async () => {
    myClubs.isError = true;
    renderApp();
    expect(await screen.findByText('клуб')).toBeInTheDocument();
  });
});
