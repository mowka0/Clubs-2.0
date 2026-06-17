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
    const { container } = render(<ClubQualityFacts clubId="c1" memberCount={42} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the three quality rings when the club has activity', () => {
    mockFacts({ meetingsPerMonth: 1.3, avgAttendance: 11, coreSize: 8, ageMonths: 14 });
    render(<ClubQualityFacts clubId="c1" memberCount={42} />);

    expect(screen.getByText('Качество клуба')).toBeInTheDocument();
    // ring centres (distinct-absolutes)
    expect(screen.getByText('8')).toBeInTheDocument();   // coreSize → Сплочённость
    expect(screen.getByText('1.3')).toBeInTheDocument(); // meetingsPerMonth → Активность
    expect(screen.getByText('11')).toBeInTheDocument();  // avgAttendance → Приходит
    expect(screen.getByText('из 42')).toBeInTheDocument(); // denominator = memberCount
    // axis labels
    expect(screen.getByText('сплочённость')).toBeInTheDocument();
    expect(screen.getByText('активность')).toBeInTheDocument();
    expect(screen.getByText('приходит')).toBeInTheDocument();
  });

  it('drops a trailing .0 and shows a 0-centre for a zero sub-metric', () => {
    mockFacts({ meetingsPerMonth: 8, avgAttendance: 0, coreSize: 5, ageMonths: 3 });
    render(<ClubQualityFacts clubId="c1" memberCount={30} />);

    expect(screen.getByText('8')).toBeInTheDocument(); // 8.0 → "8"
    expect(screen.getByText('0')).toBeInTheDocument(); // avgAttendance ring centre, empty ring
  });

  it('shows the empty state (no fake activity) when the club has no event history', () => {
    mockFacts({ meetingsPerMonth: 0, avgAttendance: 0, coreSize: 0, ageMonths: 14 });
    render(<ClubQualityFacts clubId="c1" memberCount={5} />);

    expect(screen.getByText(/Пока нет данных о встречах/)).toBeInTheDocument();
    expect(screen.getByText(/Клубу 1 год/)).toBeInTheDocument();
    expect(screen.queryByText('сплочённость')).not.toBeInTheDocument();
  });
});
