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

describe('ClubQualityFacts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing while data is unavailable (fail-soft)', () => {
    mockData(undefined);
    const { container } = render(<ClubQualityFacts clubId="c1" memberCount={42} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the three quality rings when the club has activity', () => {
    mockData(facts({ meetingsPerMonth: 1.3, avgAttendance: 11, coreSize: 8 }));
    render(<ClubQualityFacts clubId="c1" memberCount={42} />);

    expect(screen.getByText('Качество клуба')).toBeInTheDocument();
    expect(screen.getByText('8')).toBeInTheDocument();   // coreSize → Основа клуба
    expect(screen.getByText('1.3')).toBeInTheDocument(); // meetingsPerMonth → Частота встреч
    expect(screen.getByText('11')).toBeInTheDocument();  // avgAttendance → Обычно приходит
    expect(screen.getByText('из 42')).toBeInTheDocument();
    expect(screen.getByText('основа клуба')).toBeInTheDocument();
    expect(screen.getByText('частота встреч')).toBeInTheDocument();
    expect(screen.getByText('обычно приходит')).toBeInTheDocument();
  });

  it('drops a trailing .0 and shows a 0-centre for a zero sub-metric', () => {
    mockData(facts({ meetingsPerMonth: 8, avgAttendance: 0, coreSize: 5 }));
    render(<ClubQualityFacts clubId="c1" memberCount={30} />);

    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('shows the empty state when the club has no event history (age now lives in achievements)', () => {
    mockData(facts({ ageMonths: 14 }));
    render(<ClubQualityFacts clubId="c1" memberCount={5} />);

    expect(screen.getByText(/Пока нет данных о встречах/)).toBeInTheDocument();
    expect(screen.queryByText('основа клуба')).not.toBeInTheDocument();
  });
});
