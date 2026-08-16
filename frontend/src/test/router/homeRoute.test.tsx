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

const useMyClubsQueryMock = vi.fn();
vi.mock('../../queries/clubs', () => ({
  useMyClubsQuery: () => useMyClubsQueryMock(),
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

beforeEach(() => {
  useMyClubsQueryMock.mockReset();
});

describe('HomeRoute — куда ведёт «/» в чат-модели', () => {
  it('клубы ещё грузятся — спиннер, каталогом не мигаем', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ isPending: true, data: undefined }));
    renderHome();
    expect(screen.getByText('загрузка')).toBeInTheDocument();
  });

  it('ровно один клуб — сразу в него, без лишнего тапа', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ data: [{ clubId: 'abc-123' }] }));
    renderHome();
    expect(screen.getByText('страница клуба')).toBeInTheDocument();
  });

  it('несколько клубов — список «Мои клубы»', () => {
    useMyClubsQueryMock.mockReturnValue(
      queryResult({ data: [{ clubId: 'abc-123' }, { clubId: 'def-456' }] }),
    );
    renderHome();
    expect(screen.getByText('мои клубы')).toBeInTheDocument();
  });

  it('клубов нет — временно каталог (до онбординга «подключите чат»)', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ data: [] }));
    renderHome();
    expect(screen.getByText('каталог клубов')).toBeInTheDocument();
  });

  it('ошибка загрузки — «Мои клубы» с их экраном ошибки, а не пустой каталог', () => {
    useMyClubsQueryMock.mockReturnValue(queryResult({ isError: true, data: undefined }));
    renderHome();
    expect(screen.getByText('мои клубы')).toBeInTheDocument();
  });
});
