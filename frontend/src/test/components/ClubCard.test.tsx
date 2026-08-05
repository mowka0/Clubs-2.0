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
    cityId: null,
    subscriptionPrice: 0,
    memberCount: 12,
    memberLimit: 30,
    avatarUrl: null,
    coverUrl: null,
    nearestEvent: null,
    tags: [],
    interests: [],
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

describe('ClubCard — карточка Discovery (чистая обложка, вариант G)', () => {
  it('до фактов: имя и город есть, активности ещё нет', () => {
    renderCard();
    expect(screen.getByText('Беговой клуб')).toBeInTheDocument();
    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.queryByText(/актив/)).not.toBeInTheDocument();
  });

  it('с фактами: размер и активность в строке города, возраста клуба нет', () => {
    renderCard({
      club: club({ memberCount: 24 }),
      facts: facts({ ageDays: 145, engagementPercent: 78 }),
    });

    expect(screen.getByText('Москва')).toBeInTheDocument();
    expect(screen.getByText('24 чел')).toBeInTheDocument();
    expect(screen.getByText('78% актив')).toBeInTheDocument();
    // Возраст клуба снят вместе с полкой метрик — самый слабый сигнал при выборе.
    expect(screen.queryByText(/145/)).not.toBeInTheDocument();
    expect(screen.queryByText(/дн/)).not.toBeInTheDocument();
  });

  it('полки метрик на обложке больше нет — фото не режется', () => {
    const { container } = renderCard({ facts: facts({ ageDays: 10, engagementPercent: 50 }) });
    expect(container.querySelector('.rd-shelf')).toBeNull();
    // Единственное наложение на обложке — чип цены.
    expect(container.querySelector('.rd-cover .rd-price-chip')).not.toBeNull();
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

  it('темы клуба идут плашками в порядке разметки', () => {
    const { container } = renderCard({ club: club({ interests: ['бег', 'марафон'] }) });
    const topics = [...container.querySelectorAll('.rd-topic')].map((n) => n.textContent);
    expect(topics).toEqual(['бег', 'марафон']);
  });

  it('лишние темы сворачиваются в «+N» — ряд не переносится', () => {
    const { container } = renderCard({ club: club({ interests: ['бег', 'марафон', 'трейл', 'йога', 'бокс'] }) });
    const topics = [...container.querySelectorAll('.rd-topic')].map((n) => n.textContent);
    expect(topics).toEqual(['бег', 'марафон', 'трейл', '+2']);
  });

  it('у клуба без тем строки тем нет вовсе', () => {
    const { container } = renderCard({ club: club({ interests: [] }) });
    expect(container.querySelector('.rd-topics')).toBeNull();
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
