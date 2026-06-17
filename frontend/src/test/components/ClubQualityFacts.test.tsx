import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ClubQualityFacts } from '../../components/club/ClubQualityFacts';
import { useClubQualityQuery } from '../../queries/clubQuality';
import type { ClubFactsDto } from '../../types/api';

vi.mock('../../queries/clubQuality', () => ({
  useClubQualityQuery: vi.fn(),
}));

const mockedQuery = vi.mocked(useClubQualityQuery);

function mockFacts(data: ClubFactsDto | undefined) {
  mockedQuery.mockReturnValue({ data } as ReturnType<typeof useClubQualityQuery>);
}

describe('ClubQualityFacts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing while data is unavailable (fail-soft)', () => {
    mockFacts(undefined);
    const { container } = render(<ClubQualityFacts clubId="c1" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the four fact tiles when the club has activity', () => {
    mockFacts({ meetingsPerMonth: 1.3, avgAttendance: 11, coreSize: 8, ageMonths: 14 });
    render(<ClubQualityFacts clubId="c1" />);

    expect(screen.getByText('Качество клуба')).toBeInTheDocument();
    expect(screen.getByText('1.3')).toBeInTheDocument(); // meetingsPerMonth keeps one decimal
    expect(screen.getByText('11')).toBeInTheDocument();   // avgAttendance
    expect(screen.getByText('8')).toBeInTheDocument();    // coreSize
    expect(screen.getByText('1')).toBeInTheDocument();    // 14 months → 1 year
    expect(screen.getByText('год')).toBeInTheDocument();
  });

  it('drops a trailing .0 and shows «—» for zero sub-metrics', () => {
    mockFacts({ meetingsPerMonth: 8, avgAttendance: 0, coreSize: 0, ageMonths: 3 });
    render(<ClubQualityFacts clubId="c1" />);

    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(2); // avgAttendance + coreSize
  });

  it('shows the empty state (no fake activity) when the club has no event history', () => {
    mockFacts({ meetingsPerMonth: 0, avgAttendance: 0, coreSize: 0, ageMonths: 14 });
    render(<ClubQualityFacts clubId="c1" />);

    expect(screen.getByText(/Пока нет данных о встречах/)).toBeInTheDocument();
    expect(screen.getByText(/Клубу 1 год/)).toBeInTheDocument();
  });
});
