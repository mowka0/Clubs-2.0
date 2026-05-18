import { describe, expect, it } from 'vitest';
import { groupMyEvents } from '../../utils/feedGrouping';
import type { MyEventListItemDto } from '../../types/api';

function buildEvent(overrides: Partial<MyEventListItemDto>): MyEventListItemDto {
  return {
    id: overrides.id ?? '00000000-0000-0000-0000-000000000000',
    title: 'Test',
    eventDatetime: overrides.eventDatetime ?? new Date().toISOString(),
    locationText: 'Place',
    status: overrides.status ?? 'upcoming',
    clubId: '00000000-0000-0000-0000-000000000001',
    clubName: 'Club',
    clubAvatarUrl: null,
    myVote: null,
    myParticipationStatus: null,
    goingCount: 0,
    confirmedCount: 0,
    participantLimit: 10,
    actionRequired: overrides.actionRequired ?? false,
    ...overrides,
  };
}

describe('groupMyEvents', () => {
  const now = new Date('2026-05-18T10:00:00Z');

  it('returns empty list when no events', () => {
    expect(groupMyEvents([], now)).toEqual([]);
  });

  it('groups action-required events first regardless of date', () => {
    const inThreeDays = new Date(now.getTime() + 3 * 24 * 60 * 60 * 1000).toISOString();
    const inTwentyDays = new Date(now.getTime() + 20 * 24 * 60 * 60 * 1000).toISOString();

    const events = [
      buildEvent({ id: 'a', eventDatetime: inThreeDays, actionRequired: false }),
      buildEvent({ id: 'b', eventDatetime: inTwentyDays, actionRequired: true }),
    ];

    const sections = groupMyEvents(events, now);
    expect(sections[0]?.key).toBe('action_required');
    expect(sections[0]?.events.map((e) => e.id)).toEqual(['b']);
  });

  it('splits remaining events into this_week and later by 7-day horizon', () => {
    const inOneDay = new Date(now.getTime() + 1 * 24 * 60 * 60 * 1000).toISOString();
    const inSevenDays = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString();
    const inTenDays = new Date(now.getTime() + 10 * 24 * 60 * 60 * 1000).toISOString();

    const events = [
      buildEvent({ id: 'a', eventDatetime: inOneDay }),
      buildEvent({ id: 'b', eventDatetime: inSevenDays }),
      buildEvent({ id: 'c', eventDatetime: inTenDays }),
    ];

    const sections = groupMyEvents(events, now);
    expect(sections.map((s) => s.key)).toEqual(['this_week', 'later']);
    expect(sections[0]?.events.map((e) => e.id)).toEqual(['a', 'b']);
    expect(sections[1]?.events.map((e) => e.id)).toEqual(['c']);
  });

  it('omits empty sections', () => {
    const inTenDays = new Date(now.getTime() + 10 * 24 * 60 * 60 * 1000).toISOString();
    const sections = groupMyEvents(
      [buildEvent({ eventDatetime: inTenDays })],
      now,
    );
    expect(sections).toHaveLength(1);
    expect(sections[0]?.key).toBe('later');
  });

  it('produces all three sections when mixed', () => {
    const inOneDay = new Date(now.getTime() + 1 * 24 * 60 * 60 * 1000).toISOString();
    const inTenDays = new Date(now.getTime() + 10 * 24 * 60 * 60 * 1000).toISOString();
    const events = [
      buildEvent({ id: 'urgent', actionRequired: true, eventDatetime: inOneDay }),
      buildEvent({ id: 'week',   actionRequired: false, eventDatetime: inOneDay }),
      buildEvent({ id: 'later',  actionRequired: false, eventDatetime: inTenDays }),
    ];
    const sections = groupMyEvents(events, now);
    expect(sections.map((s) => s.key)).toEqual(['action_required', 'this_week', 'later']);
  });
});
