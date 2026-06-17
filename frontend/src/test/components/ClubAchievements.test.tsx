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

  it('always shows the age badge, even for a brand-new club with no counters', () => {
    mockData(facts({ ageMonths: 2 }));
    render(<ClubAchievements clubId="c1" />);
    expect(screen.getByText('Достижения')).toBeInTheDocument();
    expect(screen.getByText('Клубу 2 мес')).toBeInTheDocument();
    expect(screen.queryByText(/встреч/)).not.toBeInTheDocument();
  });

  it('renders the age badge + live activity counters (no thresholds/locks)', () => {
    mockData(facts({ ageMonths: 14, totalMeetings: 71, successfulSkladchinas: 3 }));
    render(<ClubAchievements clubId="c1" />);
    expect(screen.getByText('Год клубу')).toBeInTheDocument();
    expect(screen.getByText('71 встреча')).toBeInTheDocument();
    expect(screen.getByText('3 сбора')).toBeInTheDocument();
  });
});
