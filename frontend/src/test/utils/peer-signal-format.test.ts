import { describe, it, expect } from 'vitest';
import {
  formatReliabilityHeadline,
  formatPeerSignal,
} from '../../features/applications-inbox/lib/peer-signal-format';
import type { PeerStatsDto } from '../../types/api';

const base: PeerStatsDto = {
  memberClubCount: 0,
  totalConfirmations: 0,
  totalAttendances: 0,
  reliableClubs: 0,
  trackRecordClubs: 0,
  level: 1,
  levelName: 'Гость',
  levelTier: 'base',
};

describe('formatReliabilityHeadline', () => {
  it('uses the genitive «клуба» after 1 (and counts ending in 1 except 11)', () => {
    expect(formatReliabilityHeadline(1, 1)).toBe('Надёжен в 1 из 1 клуба');
    expect(formatReliabilityHeadline(20, 21)).toBe('Надёжен в 20 из 21 клуба');
  });

  it('uses «клубов» for 2–4, 5+, and 11', () => {
    expect(formatReliabilityHeadline(7, 8)).toBe('Надёжен в 7 из 8 клубов');
    expect(formatReliabilityHeadline(2, 2)).toBe('Надёжен в 2 из 2 клубов');
    expect(formatReliabilityHeadline(10, 11)).toBe('Надёжен в 10 из 11 клубов');
  });
});

describe('formatPeerSignal', () => {
  it('flags a brand-new applicant', () => {
    expect(formatPeerSignal(base)).toBe('Новый пользователь');
  });

  it('handles clubs without any stage-2 events yet', () => {
    expect(formatPeerSignal({ ...base, memberClubCount: 2 })).toBe('В 2 клубах · ещё не было событий');
  });

  it('reports attendance once there are confirmations', () => {
    expect(
      formatPeerSignal({ ...base, memberClubCount: 1, totalConfirmations: 5, totalAttendances: 4 }),
    ).toBe('В 1 клубе · посетил 4 из 5 событий');
  });
});
