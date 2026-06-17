import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ClubAchievements } from '../../components/club/ClubAchievements';
import { useClubQualityQuery } from '../../queries/clubQuality';
import type { ClubFactsDto } from '../../types/api';

vi.mock('../../queries/clubQuality', () => ({ useClubQualityQuery: vi.fn() }));
const mockedQuery = vi.mocked(useClubQualityQuery);

function facts(o: Partial<ClubFactsDto> = {}): ClubFactsDto {
  return {
    meetingsPerMonth: 0, avgAttendance: 0, coreSize: 0, ageMonths: 0,
    totalMeetings: 0, successfulSkladchinas: 0, ...o,
  };
}
function mockData(d: ClubFactsDto | undefined) {
  mockedQuery.mockReturnValue({ data: d } as ReturnType<typeof useClubQualityQuery>);
}

describe('ClubAchievements', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing while data is unavailable (fail-soft)', () => {
    mockData(undefined);
    const { container } = render(<ClubAchievements clubId="c1" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('always shows the age badge, even for a brand-new club with no milestones', () => {
    mockData(facts({ ageMonths: 2 }));
    render(<ClubAchievements clubId="c1" />);
    expect(screen.getByText('Достижения')).toBeInTheDocument();
    expect(screen.getByText('Клубу 2 мес')).toBeInTheDocument();
  });

  it('renders earned trophies and an in-progress goal', () => {
    mockData(facts({ ageMonths: 14, coreSize: 8, totalMeetings: 71, successfulSkladchinas: 1 }));
    render(<ClubAchievements clubId="c1" />);
    expect(screen.getByText('Год клубу')).toBeInTheDocument();
    expect(screen.getByText('5 преданных')).toBeInTheDocument();
    expect(screen.getByText('50 встреч')).toBeInTheDocument();
    expect(screen.getByText('Первый сбор')).toBeInTheDocument();
    expect(screen.getByText('100 встреч · 71/100')).toBeInTheDocument(); // locked goal with progress
  });
});
