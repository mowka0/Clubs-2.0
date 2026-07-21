import { describe, expect, it } from 'vitest';
import { groupMyEvents } from '../../utils/feedGrouping';
import type { MyEventListItemDto } from '../../types/api';

function buildEvent(overrides: Partial<MyEventListItemDto>): MyEventListItemDto {
  return {
    id: overrides.id ?? '00000000-0000-0000-0000-000000000000',
    title: 'Test',
    eventDatetime: overrides.eventDatetime ?? new Date().toISOString(),
    locationText: 'Place',
    photoUrl: null,
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
    isHistory: overrides.isHistory ?? false,
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

  // --- Итерация 5: секция «История» ---

  it('places history section last, after action/this_week/later', () => {
    const inOneDay = new Date(now.getTime() + 1 * 24 * 60 * 60 * 1000).toISOString();
    const inTenDays = new Date(now.getTime() + 10 * 24 * 60 * 60 * 1000).toISOString();
    const yesterday = new Date(now.getTime() - 1 * 24 * 60 * 60 * 1000).toISOString();

    const events = [
      buildEvent({ id: 'urgent', actionRequired: true, eventDatetime: inOneDay }),
      buildEvent({ id: 'week',   eventDatetime: inOneDay }),
      buildEvent({ id: 'later',  eventDatetime: inTenDays }),
      buildEvent({ id: 'hist',   eventDatetime: yesterday, isHistory: true }),
    ];
    const sections = groupMyEvents(events, now);
    expect(sections.map((s) => s.key)).toEqual(['action_required', 'this_week', 'later', 'history']);
    const last = sections[sections.length - 1];
    expect(last?.title).toBe('История');
    expect(last?.events.map((e) => e.id)).toEqual(['hist']);
  });

  it('keeps history order as delivered by backend, without re-sorting by date', () => {
    // Бэкенд отдаёт историю недавними первыми — здесь массив специально НЕ по возрастанию дат:
    // recent (позже по дате) идёт раньше old (раньше по дате). Клиент обязан сохранить порядок.
    const recent = new Date(now.getTime() - 1 * 24 * 60 * 60 * 1000).toISOString();
    const old = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000).toISOString();

    const events = [
      buildEvent({ id: 'recent', eventDatetime: recent, isHistory: true }),
      buildEvent({ id: 'old',    eventDatetime: old,    isHistory: true }),
    ];
    const sections = groupMyEvents(events, now);
    expect(sections.map((s) => s.key)).toEqual(['history']);
    // Порядок массива сохранён (recent, old), а не пересортирован по возрастанию даты (old, recent).
    expect(sections[0]?.events.map((e) => e.id)).toEqual(['recent', 'old']);
  });

  it('routes isHistory event to history even when status is stage_2 (cron lag, AC-H14)', () => {
    // Событие прошло 2ч назад, крон завершения ещё не отработал (status = stage_2), но явка
    // отмечена → isHistory = true. Историчность определяется ТОЛЬКО по isHistory, не по status
    // и не по дате — иначе строка провалилась бы в «Эта неделя».
    const twoHoursAgo = new Date(now.getTime() - 2 * 60 * 60 * 1000).toISOString();
    const events = [
      buildEvent({ id: 'lagged', status: 'stage_2', eventDatetime: twoHoursAgo, isHistory: true }),
    ];
    const sections = groupMyEvents(events, now);
    expect(sections.map((s) => s.key)).toEqual(['history']);
    expect(sections[0]?.events[0]?.id).toBe('lagged');
  });
});
