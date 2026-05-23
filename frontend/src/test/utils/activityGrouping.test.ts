import { describe, expect, it } from 'vitest';
import { groupActivitiesByDay } from '../../utils/activityGrouping';
import type {
  ActivityItemDto,
  EventActivityDto,
  SkladchinaActivityDto,
} from '../../api/activities';

function buildEvent(overrides: Partial<EventActivityDto> = {}): EventActivityDto {
  return {
    type: 'event',
    id: overrides.id ?? '11111111-1111-1111-1111-111111111111',
    clubId: 'club-1',
    title: 'Yoga',
    createdAt: overrides.createdAt ?? '2026-05-23T10:00:00Z',
    isCompleted: false,
    eventDatetime: '2026-05-30T10:00:00Z',
    locationText: 'Park',
    participantLimit: 20,
    goingCount: 5,
    status: 'upcoming',
    descriptionPreview: null,
    ...overrides,
  };
}

function buildSkladchina(
  overrides: Partial<SkladchinaActivityDto> = {},
): SkladchinaActivityDto {
  return {
    type: 'skladchina',
    id: overrides.id ?? '22222222-2222-2222-2222-222222222222',
    clubId: 'club-1',
    title: 'Sauna',
    createdAt: overrides.createdAt ?? '2026-05-23T11:00:00Z',
    isCompleted: false,
    paymentMode: 'fixed_equal',
    totalGoalKopecks: 500000,
    collectedKopecks: 100000,
    deadline: '2026-05-30T23:59:00Z',
    participantCount: 5,
    paidCount: 1,
    status: 'active',
    affectsReputation: false,
    ...overrides,
  };
}

describe('groupActivitiesByDay', () => {
  // Use a local-time anchor so SЕГОДНЯ/ВЧЕРА tests don't drift across TZ.
  const now = new Date(2026, 4, 23, 12, 0, 0); // 2026-05-23 12:00 local

  function localIso(year: number, month: number, day: number, hour = 12, minute = 0): string {
    return new Date(year, month, day, hour, minute, 0).toISOString();
  }

  it('returns empty list for empty input', () => {
    expect(groupActivitiesByDay([], now)).toEqual([]);
  });

  it('labels today as СЕГОДНЯ', () => {
    const today = buildEvent({ id: 'a', createdAt: localIso(2026, 4, 23, 10) });
    const groups = groupActivitiesByDay([today], now);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.dayLabel).toBe('СЕГОДНЯ');
    expect(groups[0]?.items.map((i) => i.id)).toEqual(['a']);
  });

  it('labels yesterday as ВЧЕРА', () => {
    const yesterday = buildEvent({ id: 'a', createdAt: localIso(2026, 4, 22, 10) });
    const groups = groupActivitiesByDay([yesterday], now);
    expect(groups[0]?.dayLabel).toBe('ВЧЕРА');
  });

  it('labels older days as "D MMM" (Russian short month)', () => {
    const old = buildEvent({ id: 'a', createdAt: localIso(2026, 4, 4, 10) }); // 4 мая 2026
    const groups = groupActivitiesByDay([old], now);
    expect(groups[0]?.dayLabel).toBe('4 мая');
  });

  it('groups items of mixed types into one day-bucket and preserves order', () => {
    const e1 = buildEvent({ id: 'e1', createdAt: localIso(2026, 4, 23, 14) });
    const s1 = buildSkladchina({ id: 's1', createdAt: localIso(2026, 4, 23, 11) });
    const groups = groupActivitiesByDay([e1, s1], now);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.dayLabel).toBe('СЕГОДНЯ');
    expect(groups[0]?.items.map((i) => i.id)).toEqual(['e1', 's1']);
  });

  it('splits across multiple day buckets and keeps section order from input', () => {
    const a: ActivityItemDto[] = [
      buildEvent({ id: 'today', createdAt: localIso(2026, 4, 23, 9) }),
      buildSkladchina({ id: 'yesterday', createdAt: localIso(2026, 4, 22, 18) }),
      buildEvent({ id: 'old', createdAt: localIso(2026, 4, 4, 10) }),
    ];
    const groups = groupActivitiesByDay(a, now);
    expect(groups.map((g) => g.dayLabel)).toEqual(['СЕГОДНЯ', 'ВЧЕРА', '4 мая']);
  });
});
