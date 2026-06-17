import { describe, it, expect } from 'vitest';
import { ageBadge, milestones } from '../../components/club/clubMilestones';
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

describe('milestones (laddered)', () => {
  it('brand-new club → no milestones', () => {
    expect(milestones(facts())).toEqual([]);
  });

  it('highest reached tier (earned) + next tier (goal with progress)', () => {
    const m = milestones(facts({ coreSize: 8, totalMeetings: 71 }));
    expect(m).toContainEqual({ icon: '🤝', label: '5 преданных', earned: true });
    expect(m).toContainEqual({ icon: '🤝', label: '10 преданных', earned: false, current: 8, target: 10 });
    expect(m).toContainEqual({ icon: '🔥', label: '50 встреч', earned: true });
    expect(m).toContainEqual({ icon: '🔥', label: '100 встреч', earned: false, current: 71, target: 100 });
  });

  it('a started-but-sub-first-tier metric shows only the next goal', () => {
    expect(milestones(facts({ coreSize: 3 }))).toEqual([
      { icon: '🤝', label: '5 преданных', earned: false, current: 3, target: 5 },
    ]);
  });

  it('skladchina ladder: «Первый сбор» then «N сборов» with a progress goal', () => {
    const first = milestones(facts({ successfulSkladchinas: 1 }));
    expect(first).toContainEqual({ icon: '💸', label: 'Первый сбор', earned: true });
    expect(first).toContainEqual({ icon: '💸', label: '5 сборов', earned: false, current: 1, target: 5 });

    const seven = milestones(facts({ successfulSkladchinas: 7 }));
    expect(seven).toContainEqual({ icon: '💸', label: '5 сборов', earned: true });
    expect(seven).toContainEqual({ icon: '💸', label: '10 сборов', earned: false, current: 7, target: 10 });

    expect(milestones(facts({ successfulSkladchinas: 0 })).some((m) => m.icon === '💸')).toBe(false);
  });

  it('caps at the top tier with no further goal', () => {
    const m = milestones(facts({ totalMeetings: 600 }));
    expect(m).toContainEqual({ icon: '🔥', label: '500 встреч', earned: true });
    expect(m.some((x) => !x.earned)).toBe(false);
  });
});
