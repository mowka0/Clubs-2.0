import { describe, it, expect } from 'vitest';
import { ageBadge, counters } from '../../components/club/clubMilestones';
import type { ClubFactsDto } from '../../types/api';

function facts(o: Partial<ClubFactsDto> = {}): ClubFactsDto {
  return {
    meetingsPerMonth: 0, avgAttendance: 0, coreSize: 0, ageMonths: 0,
    totalMeetings: 0, successfulSkladchinas: 0, ...o,
  };
}

describe('ageBadge', () => {
  it('months under a year', () => {
    expect(ageBadge(0).label).toBe('Клубу меньше месяца');
    expect(ageBadge(5).label).toBe('Клубу 5 мес');
  });
  it('12–23 months → «Год клубу»', () => {
    expect(ageBadge(12).label).toBe('Год клубу');
    expect(ageBadge(23).label).toBe('Год клубу');
  });
  it('years with correct plural', () => {
    expect(ageBadge(24).label).toBe('Клубу 2 года');
    expect(ageBadge(60).label).toBe('Клубу 5 лет');
  });
});

describe('counters (живые итоги, без порогов)', () => {
  it('empty club → no counters (only the always-on age badge lives outside)', () => {
    expect(counters(facts())).toEqual([]);
  });

  it('lifetime meetings with correct plural', () => {
    expect(counters(facts({ totalMeetings: 71 }))).toContainEqual({ icon: '🔥', label: '71 встреча' });
    expect(counters(facts({ totalMeetings: 5 }))).toContainEqual({ icon: '🔥', label: '5 встреч' });
    expect(counters(facts({ totalMeetings: 2 }))).toContainEqual({ icon: '🔥', label: '2 встречи' });
  });

  it('successful skladchinas with correct plural', () => {
    expect(counters(facts({ successfulSkladchinas: 1 }))).toContainEqual({ icon: '💸', label: '1 сбор' });
    expect(counters(facts({ successfulSkladchinas: 3 }))).toContainEqual({ icon: '💸', label: '3 сбора' });
    expect(counters(facts({ successfulSkladchinas: 8 }))).toContainEqual({ icon: '💸', label: '8 сборов' });
  });

  it('hides a zero counter', () => {
    expect(counters(facts({ totalMeetings: 4 })).some((a) => a.icon === '💸')).toBe(false);
  });
});
