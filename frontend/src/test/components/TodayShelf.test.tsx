import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { TodayShelf } from '../../components/TodayShelf';
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

/** «Сейчас» зафиксировано: полка сравнивает календарные даты, тест не должен зависеть от часов CI. */
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
      <TodayShelf clubs={clubs} />
    </MemoryRouter>,
  );
}

describe('TodayShelf — полка «Встречаются сегодня»', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: NOW, toFake: ['Date'] });
  });

  afterEach(() => {
    vi.useRealTimers();
    navigateMock.mockReset();
  });

  it('фильтрует к клубам с событием сегодня (календарная дата, не окно 24ч)', () => {
    renderShelf([
      club({ id: 'today', name: 'Сегодняшний', nearestEvent: event() }),
      // Завтра утром — в окно «24 часа» попало бы, но полка календарная
      club({
        id: 'tomorrow',
        name: 'Завтрашний',
        nearestEvent: event({ id: 'e2', eventDatetime: '2026-07-23T09:00:00' }),
      }),
      club({ id: 'none', name: 'Без события', nearestEvent: null }),
    ]);

    expect(screen.getByText('Встречаются сегодня')).toBeInTheDocument();
    expect(screen.getByText('Сегодняшний')).toBeInTheDocument();
    expect(screen.queryByText('Завтрашний')).not.toBeInTheDocument();
    expect(screen.queryByText('Без события')).not.toBeInTheDocument();
  });

  it('скрыта целиком, когда клубов с событием сегодня нет', () => {
    renderShelf([club({ nearestEvent: null })]);
    expect(screen.queryByText('Встречаются сегодня')).not.toBeInTheDocument();
  });

  it('рендерит время HH:MM, название и «категория · участники»', () => {
    renderShelf([club({ nearestEvent: event({ eventDatetime: '2026-07-22T19:30:00' }) })]);

    expect(screen.getByText('19:30')).toBeInTheDocument();
    expect(screen.getByText('сегодня')).toBeInTheDocument();
    expect(screen.getByText('Беговой клуб')).toBeInTheDocument();
    expect(screen.getByText('Спорт · 24 участника')).toBeInTheDocument();
  });

  it('сортирует по времени события по возрастанию', () => {
    renderShelf([
      club({ id: 'late', name: 'Поздний', nearestEvent: event({ eventDatetime: '2026-07-22T20:00:00' }) }),
      club({ id: 'early', name: 'Ранний', nearestEvent: event({ eventDatetime: '2026-07-22T18:00:00' }) }),
    ]);

    const names = screen.getAllByText(/Ранний|Поздний/).map((el) => el.textContent);
    expect(names).toEqual(['Ранний', 'Поздний']);
  });

  it('клик по мини-карточке ведёт на страницу клуба', () => {
    renderShelf([club({ id: 'club-42', nearestEvent: event() })]);
    fireEvent.click(screen.getByRole('button', { name: /Беговой клуб/ }));
    expect(navigateMock).toHaveBeenCalledWith('/clubs/club-42');
  });
});
