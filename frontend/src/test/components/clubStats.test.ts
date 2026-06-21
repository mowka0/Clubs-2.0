import { describe, it, expect } from 'vitest';
import { buildLevers, buildNudges, buildAttention } from '../../components/manage/clubStats';
import type { ClubStatsDto } from '../../types/api';

function stats(o: Partial<ClubStatsDto> = {}): ClubStatsDto {
  return {
    clubType: 'paid',
    retentionPercent: null,
    retentionTrend: null,
    churnedThisPeriod: 0,
    rejoinedThisPeriod: 0,
    engagementPercent: 80,
    engagementTrend: null,
    skladchinaPaidPercent: null,
    skladchinaPaidTrend: null,
    pendingApplications: null,
    stalePendingApplications: null,
    attendanceDisputes: 0,
    totalMeetings: 0,
    autoRejectedApplications: null,
    cancelledMeetings: 0,
    ...o,
  };
}

describe('buildLevers', () => {
  it('paid club shows retention + «Не продлили», hides rejoined', () => {
    const levers = buildLevers(stats({ clubType: 'paid', retentionPercent: 78, churnedThisPeriod: 3 }));
    const keys = levers.map((l) => l.key);
    expect(keys).toContain('retention');
    expect(keys).toContain('churned');
    expect(keys).not.toContain('rejoined');
    expect(levers.find((l) => l.key === 'churned')?.label).toBe('Не продлили за месяц');
    expect(levers.find((l) => l.key === 'retention')?.value).toBe('78%');
  });

  it('free club hides retention, shows «Ушли» + «Вернулись»', () => {
    const levers = buildLevers(stats({ clubType: 'free', churnedThisPeriod: 2, rejoinedThisPeriod: 1 }));
    const keys = levers.map((l) => l.key);
    expect(keys).not.toContain('retention');
    expect(keys).toContain('rejoined');
    expect(levers.find((l) => l.key === 'churned')?.label).toBe('Ушли за месяц');
  });

  it('omits skladchina and application levers when their data is null', () => {
    const keys = buildLevers(stats()).map((l) => l.key);
    expect(keys).not.toContain('skladchina');
    expect(keys).not.toContain('pending');
  });

  it('tones a percent lever: ≥75 ok, <50 bad', () => {
    expect(buildLevers(stats({ engagementPercent: 90 })).find((l) => l.key === 'engagement')?.tone).toBe('ok');
    expect(buildLevers(stats({ engagementPercent: 40 })).find((l) => l.key === 'engagement')?.tone).toBe('bad');
  });
});

describe('buildNudges', () => {
  it('answer-applications nudge turns red when some are stale', () => {
    const red = buildNudges(stats({ pendingApplications: 2, stalePendingApplications: 1 }));
    expect(red.find((n) => n.key === 'answer_applications')?.severity).toBe('red');

    const normal = buildNudges(stats({ pendingApplications: 2, stalePendingApplications: 0 }));
    expect(normal.find((n) => n.key === 'answer_applications')?.severity).toBe('normal');
  });

  it('win-back fires on churn, engagement reminder fires below 70%', () => {
    const nudges = buildNudges(stats({ churnedThisPeriod: 3, engagementPercent: 55 }));
    const keys = nudges.map((n) => n.key);
    expect(keys).toContain('win_back');
    expect(keys).toContain('remind_engagement');
  });

  it('no nudges when everything is healthy', () => {
    expect(buildNudges(stats({ engagementPercent: 85 }))).toEqual([]);
  });
});

describe('buildAttention', () => {
  it('disputes show «N из M» and read green at zero', () => {
    const items = buildAttention(stats({ attendanceDisputes: 1, totalMeetings: 71 }));
    const disputes = items.find((i) => i.key === 'disputes');
    expect(disputes?.value).toBe('1 из 71');
    expect(buildAttention(stats({ totalMeetings: 71 })).find((i) => i.key === 'disputes')?.tone).toBe('ok');
  });

  it('hides auto-rejects when the club takes no applications', () => {
    expect(buildAttention(stats()).map((i) => i.key)).not.toContain('auto_rejected');
    expect(buildAttention(stats({ autoRejectedApplications: 3 })).map((i) => i.key)).toContain('auto_rejected');
  });
});
