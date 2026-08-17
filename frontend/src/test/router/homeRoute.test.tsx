import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

// Каталог и спиннер стабим: предмет теста — куда «/» уводит человека, а не что рисуют
// эти экраны. Layout вдобавок тянет Telegram SDK, который здесь не нужен.
vi.mock('../../pages/DiscoveryPage', () => ({
  DiscoveryPage: () => <div>каталог клубов</div>,
}));
vi.mock('../../components/Layout', () => ({
  PageFallback: () => <div>загрузка</div>,
}));
vi.mock('../../components/ConnectChatScreen', () => ({
  ConnectChatScreen: () => <div>подключите чат</div>,
}));

const useMyClubsQueryMock = vi.fn();
const useClubQueryMock = vi.fn();
vi.mock('../../queries/clubs', () => ({
  useMyClubsQuery: () => useMyClubsQueryMock(),
  useClubQuery: () => useClubQueryMock(),
}));
vi.mock('../../components/ChatConnectedScreen', () => ({
  ChatConnectedScreen: () => <div>укажите город</div>,
}));

import { HomeRoute } from '../../components/HomeRoute';

/** Ответ useMyClubsQuery в том виде, в каком его читает HomeRoute. */
function queryResult(over: Partial<{ isPending: boolean; isError: boolean; data: unknown }>) {
  return { isPending: false, isError: false, data: [], ...over };
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route path="/my-clubs" element={<div>мои клубы</div>} />
        <Route path="/clubs/:id" element={<div>страница клуба</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** Членство владельца: только менеджер может сохранить город, см. HomeRoute. */
function ownerMembership(clubId: string) {
  return { clubId, role: 'organizer', status: 'active' };
}

beforeEach(() => {
  useMyClubsQueryMock.mockReset();
  useClubQueryMock.mockReset();
  // По умолчанию у клуба город уже указан — ветка «спросить город» не срабатывает.
  useClubQueryMock.mockReturnValue({ isPending: false, data: { id: 'abc-123', cityId: 'city-1' } });
});

describe('HomeRoute — куда ведёт «/» в чат-модели', () => {
  it('клубы ещё грузятся — спиннер, каталогом не мигаем', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ isPending: true, data: undefined }));
    renderHome();
    expect(screen.getByText('загрузка')).toBeInTheDocument();
  });

  it('ровно один клуб — сразу в него, без лишнего тапа', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ data: [ownerMembership('abc-123')] }));
    renderHome();
    expect(screen.getByText('страница клуба')).toBeInTheDocument();
  });

  it('несколько клубов — список «Мои клубы»', () => {
    useMyClubsQueryMock.mockReturnValue(
      queryResult({ data: [ownerMembership('abc-123'), ownerMembership('def-456')] }),
    );
    renderHome();
    expect(screen.getByText('мои клубы')).toBeInTheDocument();
  });

  it('клубов нет — предложение подключить чат, а не каталог чужих клубов', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ data: [] }));
    renderHome();
    expect(screen.getByText('подключите чат')).toBeInTheDocument();
    expect(screen.queryByText('каталог клубов')).not.toBeInTheDocument();
  });

  it('ошибка загрузки — «Мои клубы» с их экраном ошибки, а не пустой каталог', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ isError: true, data: undefined }));
    renderHome();
    expect(screen.getByText('мои клубы')).toBeInTheDocument();
  });

  it('клуб только что создан из чата — сначала спрашиваем город', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ data: [ownerMembership('abc-123')] }));
    useClubQueryMock.mockReturnValue({ isPending: false, data: { id: 'abc-123', cityId: null } });
    renderHome();
    expect(screen.getByText('укажите город')).toBeInTheDocument();
  });

  it('город не указан, но человек не менеджер — сразу в клуб, а не в тупик с 403', () => {
    useMyClubsQueryMock.mockReturnValue(
      queryResult({ data: [{ clubId: 'abc-123', role: 'member', status: 'active' }] }),
    );
    useClubQueryMock.mockReturnValue({ isPending: false, data: { id: 'abc-123', cityId: null } });
    renderHome();
    expect(screen.getByText('страница клуба')).toBeInTheDocument();
  });
});
