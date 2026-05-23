import type { ActivityItemDto } from '../api/activities';

export interface ActivityDayGroup {
  dayLabel: string;
  items: ActivityItemDto[];
}

const MONTHS_RU_GENITIVE = [
  'янв', 'фев', 'мар', 'апр', 'мая', 'июн',
  'июл', 'авг', 'сен', 'окт', 'ноя', 'дек',
];

function startOfLocalDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

function formatDayLabel(date: Date, now: Date): string {
  const todayMs = startOfLocalDay(now);
  const yesterdayMs = todayMs - 24 * 60 * 60 * 1000;
  const targetMs = startOfLocalDay(date);

  if (targetMs === todayMs) return 'СЕГОДНЯ';
  if (targetMs === yesterdayMs) return 'ВЧЕРА';

  const day = date.getDate();
  const month = MONTHS_RU_GENITIVE[date.getMonth()] ?? '';
  return `${day} ${month}`;
}

/**
 * Groups activities into day-buckets based on `createdAt` in the browser's
 * local timezone. Section order follows input order (caller's responsibility
 * to pass sorted-by-createdAt-DESC); within each day the input order is
 * preserved.
 */
export function groupActivitiesByDay(
  activities: readonly ActivityItemDto[],
  now: Date = new Date(),
): ActivityDayGroup[] {
  const groups: ActivityDayGroup[] = [];
  const indexByDay = new Map<number, number>();

  for (const item of activities) {
    const createdAt = new Date(item.createdAt);
    const dayKey = startOfLocalDay(createdAt);
    const existingIdx = indexByDay.get(dayKey);
    if (existingIdx !== undefined) {
      groups[existingIdx]!.items.push(item);
      continue;
    }
    indexByDay.set(dayKey, groups.length);
    groups.push({
      dayLabel: formatDayLabel(createdAt, now),
      items: [item],
    });
  }
  return groups;
}
