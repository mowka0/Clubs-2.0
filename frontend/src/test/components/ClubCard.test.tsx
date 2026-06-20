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
  return { clubId: 'c1', ageDays: 0, engagementPercent: 0, ...o };
}

function renderCard(props: { club?: ClubListItemDto; facts?: ClubCardFactsDto } = {}) {
  return render(
    <MemoryRouter>
      <ClubCard club={props.club ?? club()} facts={props.facts} />
    </MemoryRouter>,
  );
}

describe('ClubCard — Discovery metric trio', () => {
  it('shows name + members in meta and no trio before facts load', () => {
    renderCard();
    expect(screen.getByText('Беговой клуб')).toBeInTheDocument();
    expect(screen.getByText(/12 участников/)).toBeInTheDocument();
    expect(screen.queryByText('вовлечены')).not.toBeInTheDocument();
  });

  it('renders the segmented trio возраст · участники · вовлечённость when facts arrive', () => {
    renderCard({ facts: facts({ ageDays: 425, engagementPercent: 67 }) });

    expect(screen.getByText('425')).toBeInTheDocument();
    expect(screen.getByText('дней')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();        // participants from the list, not facts
    expect(screen.getByText('участников')).toBeInTheDocument();
    expect(screen.getByText('67%')).toBeInTheDocument();
    expect(screen.getByText('вовлечены')).toBeInTheDocument();
  });

  it('pluralizes age correctly for a brand-new club', () => {
    renderCard({ facts: facts({ ageDays: 1, engagementPercent: 0 }) });
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('день')).toBeInTheDocument();
    expect(screen.queryByText('дней')).not.toBeInTheDocument();
  });
});
