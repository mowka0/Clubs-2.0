import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { ClubCard } from '../../components/ClubCard';
import type { ClubCardFactsDto, ClubListItemDto } from '../../types/api';

function club(o: Partial<ClubListItemDto> = {}): ClubListItemDto {
  return {
    id: 'c1',
    name: 'Беговой клуб',
    category: 'sport',
    accessType: 'open',
    city: 'Москва',
    subscriptionPrice: 0,
    memberCount: 12,
    memberLimit: 30,
    avatarUrl: null,
    nearestEvent: null,
    tags: [],
    ...o,
  };
}

function facts(o: Partial<ClubCardFactsDto> = {}): ClubCardFactsDto {
  return { clubId: 'c1', ageDays: 0, engagementPercent: 0, topInCategory: false, ...o };
}

function renderCard(props: { club?: ClubListItemDto; facts?: ClubCardFactsDto } = {}) {
  return render(
    <MemoryRouter>
      <ClubCard club={props.club ?? club()} facts={props.facts} />
    </MemoryRouter>,
  );
}

describe('ClubCard — карточка Discovery v2 (полка метрик на обложке)', () => {
  it('до фактов: имя + город в meta без числа участников, полки метрик нет', () => {
    renderCard();
    expect(screen.getByText('Беговой клуб')).toBeInTheDocument();
    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.queryByText(/участник/)).not.toBeInTheDocument();
    expect(screen.queryByText(/дн/)).not.toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it('с фактами: полка «возраст · участники · вовлечённость» голым текстом', () => {
    renderCard({
      club: club({ memberCount: 24 }),
      facts: facts({ ageDays: 145, engagementPercent: 78 }),
    });

    expect(screen.getByText('145 дн')).toBeInTheDocument();
    expect(screen.getByText('24')).toBeInTheDocument(); // участники из списка, не из facts
    expect(screen.getByText('78%')).toBeInTheDocument();
    // Старые словесные подписи трио ушли вместе с телом-сеткой
    expect(screen.queryByText('дней')).not.toBeInTheDocument();
    expect(screen.queryByText('участников')).not.toBeInTheDocument();
    expect(screen.queryByText('вовлечены')).not.toBeInTheDocument();
  });

  it('meta с фактами — только город, участники не дублируются', () => {
    renderCard({ facts: facts({ ageDays: 10, engagementPercent: 50 }) });
    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.queryByText(/Москва ·/)).not.toBeInTheDocument();
  });

  it('«дн» не склоняется даже для 1 дня', () => {
    renderCard({ facts: facts({ ageDays: 1, engagementPercent: 0 }) });
    expect(screen.getByText('1 дн')).toBeInTheDocument();
    expect(screen.queryByText('день')).not.toBeInTheDocument();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('событие сегодня → колонка расписания «сегодня · HH:MM» в теле', () => {
    vi.useFakeTimers({ now: new Date('2026-07-22T12:00:00'), toFake: ['Date'] });
    renderCard({
      club: club({
        nearestEvent: { id: 'e1', title: 'Встреча', eventDatetime: '2026-07-22T22:00:00', goingCount: 3 },
      }),
    });
    expect(screen.getByText('сегодня')).toBeInTheDocument();
    expect(screen.getByText('22:00')).toBeInTheDocument();
  });

  it('событие завтра → колонка «завтра · HH:MM» (календарно, даже если до него >24ч)', () => {
    vi.useFakeTimers({ now: new Date('2026-07-22T08:00:00'), toFake: ['Date'] });
    renderCard({
      club: club({
        nearestEvent: { id: 'e1', title: 'Встреча', eventDatetime: '2026-07-23T09:00:00', goingCount: 3 },
      }),
    });
    expect(screen.getByText('завтра')).toBeInTheDocument();
    expect(screen.getByText('09:00')).toBeInTheDocument();
    expect(screen.queryByText('сегодня')).not.toBeInTheDocument();
  });

  it('событие послезавтра — колонки нет', () => {
    vi.useFakeTimers({ now: new Date('2026-07-22T12:00:00'), toFake: ['Date'] });
    renderCard({
      club: club({
        nearestEvent: { id: 'e1', title: 'Встреча', eventDatetime: '2026-07-24T09:00:00', goingCount: 3 },
      }),
    });
    expect(screen.queryByText('сегодня')).not.toBeInTheDocument();
    expect(screen.queryByText('завтра')).not.toBeInTheDocument();
    expect(screen.queryByText('09:00')).not.toBeInTheDocument();
  });

  it('«завтра» через перекат месяца (31 июля → 1 августа)', () => {
    vi.useFakeTimers({ now: new Date('2026-07-31T12:00:00'), toFake: ['Date'] });
    renderCard({
      club: club({
        nearestEvent: { id: 'e1', title: 'Встреча', eventDatetime: '2026-08-01T19:00:00', goingCount: 3 },
      }),
    });
    expect(screen.getByText('завтра')).toBeInTheDocument();
    expect(screen.getByText('19:00')).toBeInTheDocument();
  });

  it('shows the "★ Топ-5 в категории" badge only when topInCategory is true', () => {
    const { rerender } = renderCard({ facts: facts({ topInCategory: false }) });
    expect(screen.queryByText('★ Топ-5 в категории')).not.toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <ClubCard club={club()} facts={facts({ topInCategory: true })} />
      </MemoryRouter>,
    );
    expect(screen.getByText('★ Топ-5 в категории')).toBeInTheDocument();
  });
});
