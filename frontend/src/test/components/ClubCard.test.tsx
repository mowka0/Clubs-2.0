import { describe, it, expect } from 'vitest';
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
  return {
    clubId: 'c1',
    meetingsPerMonth: 0,
    engagementPercent: 0,
    ageMonths: 0,
    totalMeetings: 0,
    successfulSkladchinas: 0,
    ...o,
  };
}

function renderCard(props: { club?: ClubListItemDto; facts?: ClubCardFactsDto } = {}) {
  return render(
    <MemoryRouter>
      <ClubCard club={props.club ?? club()} facts={props.facts} />
    </MemoryRouter>,
  );
}

describe('ClubCard — Discovery quality data', () => {
  it('renders only name + meta when facts are absent (batch not yet loaded)', () => {
    renderCard();
    expect(screen.getByText('Беговой клуб')).toBeInTheDocument();
    expect(screen.queryByText(/вовлечённость/)).not.toBeInTheDocument();
    expect(screen.queryByText('Год клубу')).not.toBeInTheDocument();
  });

  it('renders the metric line and age + achievement chips when facts arrive', () => {
    renderCard({
      facts: facts({
        meetingsPerMonth: 2.5,
        engagementPercent: 67,
        ageMonths: 14,
        totalMeetings: 71,
        successfulSkladchinas: 3,
      }),
    });

    expect(screen.getByText('2,5')).toBeInTheDocument(); // встреч/мес, Russian decimal
    expect(screen.getByText('67%')).toBeInTheDocument();
    expect(screen.getByText(/вовлечённость/)).toBeInTheDocument();
    expect(screen.getByText(/Год клубу/)).toBeInTheDocument();
    expect(screen.getByText(/71 встреча/)).toBeInTheDocument();
    expect(screen.getByText(/3 сбора/)).toBeInTheDocument();
  });

  it('formats a whole-number frequency without a trailing decimal', () => {
    renderCard({ facts: facts({ meetingsPerMonth: 3, ageMonths: 5 }) });
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.queryByText('3,0')).not.toBeInTheDocument();
  });

  it('shows only the age chip (no metric line) for a club with no activity', () => {
    renderCard({ facts: facts({ ageMonths: 2 }) });

    expect(screen.getByText(/Клубу 2 мес/)).toBeInTheDocument();
    expect(screen.queryByText(/вовлечённость/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/мес/)).not.toBeInTheDocument();
  });
});
