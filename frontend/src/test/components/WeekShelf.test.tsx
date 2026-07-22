import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { WeekShelf } from '../../components/WeekShelf';
import type { ClubListItemDto, NearestEventDto } from '../../types/api';

// Хаптика в jsdom недоступна — мокаем хук целиком.
vi.mock('../../hooks/useHaptic', () => ({
  useHaptic: () => ({ impact: vi.fn(), notify: vi.fn(), select: vi.fn() }),
}));

const navigateMock = vi.hoisted(() => vi.fn());
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigateMock,
}));

/** «Сейчас» зафиксировано: среда 2026-07-22 — полка сравнивает календарные дни,
    тест не должен зависеть от часов CI. */
const NOW = new Date('2026-07-22T12:00:00');

function event(o: Partial<NearestEventDto> = {}): NearestEventDto {
  return { id: 'e1', title: 'Встреча', eventDatetime: '2026-07-22T19:30:00', goingCount: 5, ...o };
}

function club(o: Partial<ClubListItemDto> = {}): ClubListItemDto {
  return {
    id: 'c1',
    name: 'Беговой клуб',
    category: 'sport',
    accessType: 'open',
    city: 'Москва',
    subscriptionPrice: 0,
    memberCount: 24,
    memberLimit: 30,
    avatarUrl: null,
    nearestEvent: null,
    tags: [],
    ...o,
  };
}

function renderShelf(clubs: ClubListItemDto[]) {
  return render(
    <MemoryRouter>
      <WeekShelf clubs={clubs} />
    </MemoryRouter>,
  );
}

describe('WeekShelf — полка «Встречаются на неделе»', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
  });

  afterEach(() => {
    vi.useRealTimers();
    navigateMock.mockReset();
  });

  it('фильтрует к ближайшим 7 календарным дням (сегодня..+6), дальше и без события — мимо', () => {
    renderShelf([
      club({ id: 'today', name: 'Сегодняшний', nearestEvent: event() }),
      club({
        id: 'plus6',
        name: 'Через шесть дней',
        nearestEvent: event({ id: 'e2', eventDatetime: '2026-07-28T09:00:00' }),
      }),
      club({
        id: 'plus7',
        name: 'Через неделю',
        nearestEvent: event({ id: 'e3', eventDatetime: '2026-07-29T09:00:00' }),
      }),
      club({ id: 'none', name: 'Без события', nearestEvent: null }),
    ]);

    expect(screen.getByText('Встречаются на неделе')).toBeInTheDocument();
    expect(screen.getByText('Сегодняшний')).toBeInTheDocument();
    expect(screen.getByText('Через шесть дней')).toBeInTheDocument();
    expect(screen.queryByText('Через неделю')).not.toBeInTheDocument();
    expect(screen.queryByText('Без события')).not.toBeInTheDocument();
  });

  it('скрыта целиком, когда на неделе никто не встречается', () => {
    renderShelf([
      club({ nearestEvent: null }),
      club({ id: 'c2', nearestEvent: event({ eventDatetime: '2026-08-15T10:00:00' }) }),
    ]);
    expect(screen.queryByText('Встречаются на неделе')).not.toBeInTheDocument();
  });

  it('бейдж дня: «сегодня», «завтра», дальше — день недели («сб»)', () => {
    renderShelf([
      club({ id: 'today', name: 'Сегодняшний', nearestEvent: event({ eventDatetime: '2026-07-22T19:30:00' }) }),
      club({ id: 'tmrw', name: 'Завтрашний', nearestEvent: event({ id: 'e2', eventDatetime: '2026-07-23T20:00:00' }) }),
      // 25 июля 2026 — суббота
      club({ id: 'sat', name: 'Субботний', nearestEvent: event({ id: 'e3', eventDatetime: '2026-07-25T11:00:00' }) }),
    ]);

    expect(screen.getByText('сегодня')).toBeInTheDocument();
    expect(screen.getByText('завтра')).toBeInTheDocument();
    expect(screen.getByText('сб')).toBeInTheDocument();
    expect(screen.getByText('19:30')).toBeInTheDocument();
    expect(screen.getByText('20:00')).toBeInTheDocument();
    expect(screen.getByText('11:00')).toBeInTheDocument();
  });

  it('сортирует по времени события по возрастанию (сквозь дни)', () => {
    renderShelf([
      club({ id: 'sat', name: 'Субботний', nearestEvent: event({ eventDatetime: '2026-07-25T09:00:00' }) }),
      club({ id: 'today', name: 'Сегодняшний', nearestEvent: event({ eventDatetime: '2026-07-22T20:00:00' }) }),
      club({ id: 'tmrw', name: 'Завтрашний', nearestEvent: event({ eventDatetime: '2026-07-23T10:00:00' }) }),
    ]);

    const names = screen.getAllByText(/Сегодняшний|Завтрашний|Субботний/).map((el) => el.textContent);
    expect(names).toEqual(['Сегодняшний', 'Завтрашний', 'Субботний']);
  });

  it('клик по мини-карточке ведёт на страницу клуба', () => {
    renderShelf([club({ id: 'club-42', nearestEvent: event() })]);
    fireEvent.click(screen.getByRole('button', { name: /Беговой клуб/ }));
    expect(navigateMock).toHaveBeenCalledWith('/clubs/club-42');
  });
});
