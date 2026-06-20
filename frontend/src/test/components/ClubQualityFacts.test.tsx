import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

import { ClubQualityFacts } from '../../components/club/ClubQualityFacts';
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

describe('ClubQualityFacts (unified block)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing while data is unavailable (fail-soft)', () => {
    mockData(undefined);
    const { container } = render(<ClubQualityFacts clubId="c1" memberCount={42} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders rings + a single caption line (age badge + live counters) when active', () => {
    mockData(facts({ meetingsPerMonth: 1.3, avgAttendance: 11, coreSize: 8, ageMonths: 12, totalMeetings: 71, successfulSkladchinas: 3 }));
    render(<ClubQualityFacts clubId="c1" memberCount={42} />);

    // rings
    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getByText('1.3')).toBeInTheDocument();
    expect(screen.getByText('11')).toBeInTheDocument();
    expect(screen.getByText('из 42')).toBeInTheDocument();
    expect(screen.getByText('основа клуба')).toBeInTheDocument();
    expect(screen.getByText('частота встреч')).toBeInTheDocument();
    expect(screen.getByText('обычно приходит')).toBeInTheDocument();
    // caption: age + counters
    expect(screen.getByText('Год клубу')).toBeInTheDocument();
    expect(screen.getByText('71 встреча')).toBeInTheDocument();
    expect(screen.getByText('3 сбора')).toBeInTheDocument();
    // no section headers anymore
    expect(screen.queryByText('Качество клуба')).not.toBeInTheDocument();
    expect(screen.queryByText('Достижения')).not.toBeInTheDocument();
  });

  it('shows only the caption (age + «пока нет встреч») for a club with no event history', () => {
    mockData(facts({ ageMonths: 2 }));
    render(<ClubQualityFacts clubId="c1" memberCount={5} />);

    expect(screen.getByText('Клубу 2 мес')).toBeInTheDocument();
    expect(screen.getByText('пока нет встреч')).toBeInTheDocument();
    expect(screen.queryByText('основа клуба')).not.toBeInTheDocument();
  });
});
